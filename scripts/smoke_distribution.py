"""仅使用 localhost 夹具独立验证并运行便携归档。"""
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from smoke_install import verify_install
from smoke_auto_review import verify_review
from smoke_dashboard import verify_dashboard
from smoke_bottom_status import verify_bottom_status

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = '0.1.0-SNAPSHOT'
NAME = 'YLCoder-harness-' + VERSION
DIST = ROOT / 'target/distributions'
PROVIDERS = {'openai', 'openai-compatible', 'openai-responses', 'ollama',
             'anthropic', 'gemini', 'azure-openai', 'langchain4j-openai'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_member(name):
    assert '\\' not in name and '\x00' not in name, name
    assert all(part not in ('', '.', '..') for part in name.rstrip('/').split('/')), name
    path = pathlib.PurePosixPath(name)
    assert not path.is_absolute() and path.parts and path.parts[0] == NAME, name
    assert all(part not in ('', '.', '..') and ':' not in part for part in path.parts), name
    for part in path.parts[1:]:
        lower = part.lower()
        assert lower not in {'.harness', '.git', 'target', 'targets', 'logs', '.env', 'credentials'}, name
        assert not lower.endswith(('.log', '.pem', '.key')), name
    return path


def archives():
    archive_paths = [DIST / (NAME + '.zip'), DIST / (NAME + '.tar.gz')]
    for path in archive_paths:
        assert path.is_file(), '请先构建交付包：' + str(path)
    sums = {}
    for line in (DIST / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?([^/\\]+)', line)
        assert match, '校验和行无效'
        assert match[2] not in sums, '独立校验和重复'
        sums[match[2]] = match[1].lower()
    for path in archive_paths:
        assert sums.get(path.name) == digest(path.read_bytes()), '归档校验和不匹配：' + path.name

    zip_files, tar_files = {}, {}
    with zipfile.ZipFile(archive_paths[0]) as archive:
        seen = set()
        for entry in archive.infolist():
            safe_member(entry.filename)
            assert entry.filename not in seen, 'ZIP 条目重复'
            seen.add(entry.filename)
            mode = entry.external_attr >> 16
            assert not stat.S_ISLNK(mode), '禁止 ZIP 符号链接'
            assert not entry.flag_bits & 1, '归档条目已加密'
            if not entry.is_dir():
                assert stat.S_IFMT(mode) in (0, stat.S_IFREG), '不支持的 ZIP 条目'
                zip_files[entry.filename] = archive.read(entry)
                if entry.filename == NAME + '/bin/ylc':
                    assert mode & 0o111, 'ZIP 启动器缺少可执行元数据'
    with tarfile.open(archive_paths[1], 'r:gz') as archive:
        seen = set()
        for entry in archive.getmembers():
            safe_member(entry.name)
            assert entry.name not in seen, 'TAR 条目重复'
            seen.add(entry.name)
            assert entry.isfile() or entry.isdir(), '禁止 TAR 链接或特殊条目'
            if entry.isfile():
                stream = archive.extractfile(entry)
                assert stream is not None
                with stream:
                    tar_files[entry.name] = stream.read()
                if entry.name == NAME + '/bin/ylc':
                    assert entry.mode & 0o111, 'TAR 启动器缺少可执行元数据'
    assert zip_files == tar_files, 'ZIP 与 TAR 解压后的文件不同'
    required = {'bin/ylc', 'bin/ylc.cmd', 'lib/ylc.jar', 'config.example.yaml', 'configs/codego.example.yaml', 'README.md', '使用手册.md', 'docs/VERIFICATION.md', 'INSTALL.md', 'manifest.json', 'install.ps1', 'scripts/install.py', 'scripts/smoke_auto_review.py'}
    required |= {'docs/LANGUAGE_TOKENS.md', 'docs/benchmarks/language-corpus-v1.json',
                 'docs/benchmarks/language-tokens-v1.json', 'scripts/compare_language_tokens.py'}
    relative = {name[len(NAME) + 1:]: data for name, data in zip_files.items()}
    assert required <= relative.keys(), '交付包缺少必需文件'
    assert any(name.startswith('docs/') for name in relative), '缺少文档'
    # 交付包范围刻意保持明确：可以增加文档，不可包含任意产物。
    required |= {'scripts/smoke_dashboard.py', 'scripts/smoke_bottom_status.py'}
    assert required <= relative.keys(), '交付包缺少终端验收脚本'
    allowed = required | {'INSTALL.md', 'configs/codego.example.yaml'}
    assert all(name in allowed or name.startswith('docs/') for name in relative), '归档中有意外文件'
    manifest = json.loads(relative['manifest.json'])
    assert manifest['version'] == VERSION
    expected = {name: digest(data) for name, data in relative.items() if name != 'manifest.json'}
    assert manifest['files'] == expected, '清单必须精确覆盖每个其他文件'
    return relative


def launch(distribution, cwd, arguments, environment):
    launcher = distribution / 'bin' / ('ylc.cmd' if os.name == 'nt' else 'ylc')
    # Windows 通过 cmd 执行真实批处理启动器；POSIX 使用可执行权限位。
    command = [str(launcher), *arguments]
    return subprocess.run(command, cwd=cwd, env=environment, capture_output=True,
                          text=True, encoding='utf-8', timeout=30)


class Fixture:
    def __init__(self, workspace, expected_output_limit=None, expected_key=None):
        self.workspace = workspace
        self.expected_output_limit = expected_output_limit
        self.expected_key = expected_key
        self.requests = []
        self.errors = []

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    assert self.path == '/v1/chat/completions'
                    if fixture.expected_key is None:
                        assert 'Authorization' not in self.headers
                    else:
                        assert self.headers.get('Authorization') == 'Bearer ' + fixture.expected_key
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    fixture.requests.append(request)
                    number = len(fixture.requests)
                    assert request.get('stream') is True
                    if fixture.expected_output_limit is not None:
                        assert request.get('max_tokens') == fixture.expected_output_limit
                    if number == 1:
                        tool, args = 'write_file', {'path': 'result.txt', 'content': 'distribution verified\n'}
                    elif number == 2:
                        assert fixture.workspace.joinpath('result.txt').read_text(encoding='utf-8') == 'distribution verified\n'
                        tool, args = 'read_file', {'path': 'result.txt'}
                    else:
                        assert number == 3
                        results = [m for m in request['messages'] if m['role'] == 'tool']
                        assert len(results) == 2
                        assert 'distribution verified' in results[-1]['content']
                        tool, args = None, None
                    message = {'role': 'assistant', 'content': '' if tool else 'Distribution verified.'}
                    if tool:
                        message['tool_calls'] = [{'id': 'dist-' + str(number), 'type': 'function',
                                                 'function': {'name': tool, 'arguments': json.dumps(args)}}]
                    body = json.dumps({'choices': [{'message': message,
                                                   'finish_reason': 'tool_calls' if tool else 'stop'}]}).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except Exception as failure:
                    fixture.errors.append(repr(failure))
                    self.close_connection = True

        return Provider


def coding_case(distribution, invocation, workspace, explicit_workspace, environment, bundled_profile=False, inline_key=False):
    # 使用无真实权限的本地测试值，分别验证变量读取和配置内密钥优先级。
    fixture_key = 'fixture-not-a-real-api-key' if bundled_profile or inline_key else None
    fixture = Fixture(workspace, 3000 if bundled_profile or inline_key else None, fixture_key)
    config = distribution / 'config.example.yaml' if bundled_profile else invocation / 'offline.yaml'
    environment = dict(environment)
    if bundled_profile:
        environment['HARNESS_API_KEY'] = fixture_key
    if inline_key:
        config = invocation / 'inline-key.yaml'
        template = (distribution / 'config.example.yaml').read_text(encoding='utf-8')
        assert 'api-key: ""' in template
        config.write_text(template.replace('api-key: ""', 'api-key: "' + fixture_key + '"', 1), encoding='utf-8')
        environment['HARNESS_API_KEY'] = 'wrong-environment-fixture-key'
    elif not bundled_profile:
        config.write_text('{}\n', encoding='utf-8')
    with ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler()) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            arguments = ['--config', str(config), '--provider', 'openai-compatible', '--model', 'offline-fixture',
                         '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                         '--quiet', '--json']
            if not bundled_profile and not inline_key:
                arguments += ['--api-key-env', 'HARNESS_DISTRIBUTION_FIXTURE_KEY']
            if explicit_workspace:
                arguments += ['--workspace', str(workspace)]
            run = launch(distribution, invocation, arguments + ['run', '在不执行 shell 命令的情况下，写入并读取夹具文件。'], environment)
            assert run.returncode == 0, (run.stdout, run.stderr)
            result = json.loads(run.stdout)
            assert result['status'] == 'COMPLETED' and result['answer'] == 'Distribution verified.', result
            assert len(fixture.requests) == 3 and not fixture.errors, fixture.errors
            assert workspace.joinpath('result.txt').read_text(encoding='utf-8') == 'distribution verified\n'
            checkpoint = json.loads(workspace.joinpath('.harness/sessions', result['session'] + '.json').read_text(encoding='utf-8'))
            if fixture_key:
                assert fixture_key not in run.stdout + run.stderr + json.dumps(checkpoint), '凭据不得出现在日志或会话中'
            assert pathlib.Path(checkpoint['workspace']).resolve() == workspace.resolve()
            assert checkpoint['status'] == 'COMPLETED'
            calls = [call['name'] for message in checkpoint['transcript'] for call in message.get('toolCalls', [])]
            assert calls == ['write_file', 'read_file'], calls
            assert not distribution.joinpath('.harness').exists()
            assert not distribution.joinpath('result.txt').exists()
            if explicit_workspace:
                assert not invocation.joinpath('.harness').exists()
                assert not invocation.joinpath('result.txt').exists()
        finally:
            server.shutdown()


def main():
    files = archives()
    environment = os.environ.copy()
    environment.pop('HARNESS_DISTRIBUTION_FIXTURE_KEY', None)
    environment.pop('HARNESS_API_KEY', None)
    with tempfile.TemporaryDirectory(prefix='harness distribution smoke ') as temporary:
        base = pathlib.Path(temporary)
        distribution = base / 'extracted archive' / NAME
        # 解压前先验证，再写入由 ZIP 取得的字节，不信任归档路径。
        for name, data in files.items():
            target = distribution / pathlib.PurePosixPath(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        if os.name != 'nt':
            (distribution / 'bin/ylc').chmod(0o755)
        else:
            console_check = subprocess.run([sys.executable, str(ROOT / 'scripts/smoke_windows_console.py'),
                                            '--launcher', str(distribution / 'bin/ylc.cmd'),
                                            '--result', str(DIST / 'windows-console.json')],
                                           cwd=ROOT, env=environment, capture_output=True, timeout=60)
            assert console_check.returncode == 0, '解压后的 Windows 控制台中文验收失败'
            assert json.loads((DIST / 'windows-console.json').read_text(encoding='utf-8'))['status'] == 'PASS'
            input_check = subprocess.run([sys.executable, str(ROOT / 'scripts/smoke_console_input.py'),
                                          '--launcher', str(distribution / 'bin/ylc.cmd'),
                                          '--result', str(DIST / 'windows-console-input.json')],
                                         cwd=ROOT, env=environment, capture_output=True, timeout=90)
            assert input_check.returncode == 0, '解压后的 Windows 中文输入、工具和审批验收失败'
            assert json.loads((DIST / 'windows-console-input.json').read_text(encoding='utf-8'))['status'] == 'PASS'
        installation = verify_install(distribution)
        (DIST / 'global-install.json').write_text(json.dumps(installation, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        review = verify_review(distribution / 'bin' / ('ylc.cmd' if os.name == 'nt' else 'ylc'))
        (DIST / 'auto-review.json').write_text(json.dumps(review, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        dashboard = verify_dashboard(distribution / 'bin' / ('ylc.cmd' if os.name == 'nt' else 'ylc'))
        (DIST / 'dashboard.json').write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        bottom = verify_bottom_status(distribution / 'bin' / ('ylc.cmd' if os.name == 'nt' else 'ylc'))
        assert bottom['status'] in ('PASS', 'SKIP'), bottom
        (DIST / 'bottom-status.json').write_text(json.dumps(bottom, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        bottom_streaming = verify_bottom_status(distribution / 'bin' / ('ylc.cmd' if os.name == 'nt' else 'ylc'),
                                               result_path=DIST / 'bottom-status-streaming.json', streaming=True)
        assert bottom_streaming['status'] in ('PASS', 'SKIP'), bottom_streaming
        (DIST / 'bottom-status-streaming.json').write_text(json.dumps(bottom_streaming, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        recount_path = base / 'language-recount.json'
        recounted = subprocess.run([sys.executable, str(distribution / 'scripts/compare_language_tokens.py'),
                                    '--output', str(recount_path)], cwd=distribution, env=environment,
                                   capture_output=True, timeout=180)
        assert recounted.returncode == 0, '解压后的双语计数脚本执行失败'
        recount = json.loads(recount_path.read_bytes())
        original = json.loads(files['docs/benchmarks/language-tokens-v1.json'])
        assert recount['entries'] == original['entries'] and recount['summary'] == original['summary'], '解压后分词结果变化'
        assert recount['artifactSha256'] == digest(files['lib/ylc.jar'])
        assert recount['corpusSha256'] == digest(files['docs/benchmarks/language-corpus-v1.json'])
        assert recount['scriptSha256'] == digest(files['scripts/compare_language_tokens.py'])
        invocation = base / 'invocation cwd with spaces'
        invocation.mkdir()
        for arguments in (['--help'], ['--version'], ['providers'], ['doctor']):
            result = launch(distribution, invocation, arguments, environment)
            assert result.returncode == 0, (arguments, result.stdout, result.stderr)
            if arguments == ['--help']:
                assert 'ylc' in result.stdout and 'run' in result.stdout and 'resume' in result.stdout
            elif arguments == ['--version']:
                assert result.stdout.strip() == 'YLCoder-harness 0.1.0', result.stdout
            elif arguments == ['providers']:
                assert set(result.stdout.splitlines()) == PROVIDERS, result.stdout
            else:
                assert 'Java:' in result.stdout and any(marker in result.stdout for marker in ('Provider connectivity is not checked', '此检查不验证厂商连接。'))
        invalid = launch(distribution, invocation, ['--invalid-distribution-smoke-option'], environment)
        assert invalid.returncode == 2, (invalid.returncode, invalid.stdout, invalid.stderr)
        coding_case(distribution, invocation, invocation, False, environment)
        explicit_cwd = base / 'second invocation cwd with spaces'
        explicit_cwd.mkdir()
        explicit_workspace = base / 'explicit coding workspace with spaces'
        explicit_workspace.mkdir()
        coding_case(distribution, explicit_cwd, explicit_workspace, True, environment)
        profile_cwd = base / 'custom profile invocation with spaces'
        profile_cwd.mkdir()
        coding_case(distribution, profile_cwd, profile_cwd, False, environment, bundled_profile=True)
        inline_cwd = base / 'inline credential invocation with spaces'
        inline_cwd.mkdir()
        coding_case(distribution, inline_cwd, inline_cwd, False, environment, inline_key=True)
    print(json.dumps({'status': 'PASS', 'archiveFormats': ['zip', 'tar.gz'], 'providerIds': len(PROVIDERS),
                      'checks': ['安全且完全相同的归档内容', '清单与独立 SHA256 校验和', 'executable metadata',
                                 '带空格的启动器路径及工作目录', 'ylc 帮助、YLCoder-harness 版本名及 providers/doctor', 'exit code 2',
                                 '隐式与显式编码工作区', '通用厂商模板，使用 localhost 地址、HARNESS_API_KEY 测试值，并验证认证及输出限制',
                                 '配置直接密钥优先于环境变量且不写入会话或日志', '中文使用手册随包交付',
                                 '独立用户安装后通过 PATH 启动，默认/显式工作区与用户配置，重复安装保留配置',
                                 '本地读写及会话', '解压包内的双语分词脚本与固定结果逐项一致']
                      + ['无工具审核子 agent、任务内缓存、用量统计、保守拒绝与失败回退']
                      + (['真实 Windows 控制台的 936/65001 初始代码页中文输出与退出码',
                          '真实 Windows 控制台 Unicode 输入、工具读取、命令审批及精确会话保存'] if os.name == 'nt' else [])}))


if __name__ == '__main__':
    main()
