"""通过 PATH 从独立工作区验证用户安装，不改真实用户 PATH/配置，仅请求 localhost。"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading


ROOT = Path(__file__).resolve().parents[1]
TASK = '跨目录 中文任务：写入并检查文件'
KEY = 'install-localhost-fixture-key'


def install(source, destination, config_dir, cwd):
    if os.name == 'nt':
        command = ['powershell.exe', '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                   '-File', str(source / 'install.ps1'), '-InstallDirectory', str(destination),
                   '-ConfigDirectory', str(config_dir), '-NoPath']
    else:
        command = [sys.executable, str(source / 'scripts/install.py'), '--install-dir', str(destination),
                   '--config-dir', str(config_dir)]
    result = subprocess.run(command, cwd=cwd, capture_output=True, timeout=45)
    assert result.returncode == 0, (result.returncode, result.stdout.decode('utf-8', errors='replace'),
                                    result.stderr.decode('utf-8', errors='replace'))


def launch(cwd, environment, arguments):
    # 使用裸命令进行系统 PATH 查找，不直接指定安装启动器。
    if os.name == 'nt':
        # 与手册相同，在 PowerShell 中执行不带扩展名的 ylc，保留多词中文参数。
        script = cwd.parent / 'invoke-global-ylc.ps1'
        script.write_text('[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n'
                          '$harnessArguments = @(ConvertFrom-Json $env:HARNESS_INSTALL_SMOKE_ARGUMENTS)\n'
                          '& ylc @harnessArguments\nexit $LASTEXITCODE\n', encoding='utf-8-sig')
        environment = dict(environment, HARNESS_INSTALL_SMOKE_ARGUMENTS=json.dumps(arguments, ensure_ascii=False))
        command = ['powershell.exe', '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(script)]
    else:
        command = ['ylc', *arguments]
    return subprocess.run(command, cwd=cwd, env=environment, capture_output=True,
                          text=True, encoding='utf-8', timeout=30)


def verify_install(source):
    requests, failures = [], []

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                assert self.path == '/v1/chat/completions'
                assert self.headers.get('Authorization') == 'Bearer ' + KEY
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                assert body['model'] == 'global-install-fixture'
                assert any(message.get('content') == TASK for message in body['messages']), [
                    (message.get('role'), message.get('content')) for message in body['messages'] if message.get('role') == 'user']
                if len(requests) % 2 == 1:
                    message = {'role': 'assistant', 'content': '', 'tool_calls': [
                        {'id': 'install-write-' + str(len(requests)), 'type': 'function', 'function': {'name': 'write_file',
                         'arguments': json.dumps({'path': '跨目录.txt', 'content': '全局启动成功\n'})}}]}
                    finish = 'tool_calls'
                else:
                    message, finish = {'role': 'assistant', 'content': '全局启动验证通过'}, 'stop'
                response = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(response)))
                self.end_headers()
                self.wfile.write(response)
            except Exception as failure:
                failures.append(repr(failure))
                self.close_connection = True

    with tempfile.TemporaryDirectory(prefix='harness install smoke ') as temporary:
        base = Path(temporary)
        destination = base / '独立安装 with spaces'
        invocation = base / '项目 A with spaces'
        explicit = base / '项目 B with spaces'
        legacy = base / '旧配置指定的目录'
        user_home = base / 'fixture-user-home'
        for directory in (invocation, explicit, legacy, user_home):
            directory.mkdir()
        config_dir = user_home / '.code-harness'
        install(source, destination, config_dir, invocation)
        template = (source / 'config.example.yaml').read_bytes()
        config = config_dir / 'config.yaml'
        assert config.read_bytes() == template, '首次安装应创建通用模板'
        expected_jar = source / 'lib/ylc.jar'
        if not expected_jar.is_file():
            expected_jar = source / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
        assert hashlib.sha256((destination / 'lib/ylc.jar').read_bytes()).digest() == hashlib.sha256(expected_jar.read_bytes()).digest()
        assert not (destination / 'config.local.yaml').exists()
        environment = os.environ.copy()
        environment['PATH'] = str(destination / 'bin') + os.pathsep + environment.get('PATH', '')
        environment.pop('JDK_JAVA_OPTIONS', None)
        # 仅为测试隔离 Java 的用户配置搜索，不读取真实用户配置或真实密钥。
        environment['JAVA_TOOL_OPTIONS'] = '-Duser.home="' + str(user_home) + '"'
        with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            fixture_config = (f'workspace: {json.dumps(str(legacy), ensure_ascii=False)}\n'
                              'provider: openai-compatible\nmodel: global-install-fixture\n'
                              f'base-url: http://127.0.0.1:{server.server_port}/v1\napi-key: {KEY}\n')
            config.write_text(fixture_config, encoding='utf-8')
            preserved = config.read_bytes()
            install(source, destination, config_dir, explicit)
            assert config.read_bytes() == preserved, '重复安装不得覆盖现有配置'
            for arguments in (['doctor'], ['--help'], ['--invalid-install-option']):
                result = launch(invocation, environment, arguments)
                assert result.returncode == (2 if arguments[0].startswith('--invalid') else 0), (result.stdout, result.stderr)
            assert not requests, '离线命令不应发起模型请求'
            first_session = None
            # 同一份用户配置指定另一个目录，也不能改变启动目录；-w 仍可显式切换。
            for cwd, target, arguments in ((invocation, invocation, []), (explicit, explicit, []),
                                            (invocation, explicit, ['-w', os.path.relpath(explicit, invocation)])):
                result = launch(cwd, environment, [*arguments, '--quiet', '--json', 'run', TASK])
                assert result.returncode == 0, (result.returncode, result.stdout, result.stderr, failures)
                data = json.loads(result.stdout)
                assert data['status'] == 'COMPLETED' and data['answer'] == '全局启动验证通过'
                checkpoint = target / '.harness/sessions' / (data['session'] + '.json')
                state = json.loads(checkpoint.read_text(encoding='utf-8'))
                assert Path(state['workspace']).resolve() == target.resolve()
                assert target.joinpath('跨目录.txt').read_text(encoding='utf-8') == '全局启动成功\n'
                assert KEY not in result.stdout + result.stderr + checkpoint.read_text(encoding='utf-8')
                if first_session is None:
                    first_session = data['session']
            resumed = launch(invocation, environment, ['--quiet', '--json', 'resume', first_session, TASK])
            assert resumed.returncode == 0, (resumed.stdout, resumed.stderr, failures)
            resumed_state = json.loads(resumed.stdout)
            assert resumed_state['session'] == first_session and resumed_state['status'] == 'COMPLETED'
            assert not legacy.joinpath('.harness').exists() and not legacy.joinpath('跨目录.txt').exists(), '旧配置目录不能产生会话或改动'
            # 显式相对配置从调用目录寻找；无效用户配置不应阻止显式配置工作。
            override = invocation / 'override.yaml'
            invalid = launch(invocation, environment, ['--config', 'missing-relative.yaml', 'run', TASK])
            assert invalid.returncode == 1 and len(requests) == 8, '缺失的显式配置应在本地失败'
            # 使用相同协议夹具，仅覆盖地址/密钥文件的选择，不接触外部网络。
            override.write_text(fixture_config, encoding='utf-8')
            config.write_text('provider: unknown-fixture-provider\n', encoding='utf-8')
            result = launch(invocation, environment, ['--config', 'override.yaml', '--quiet', '--json', 'run', TASK])
            assert result.returncode == 0, (result.stdout, result.stderr, failures)
            assert len(requests) == 10 and not failures, failures
            assert not destination.joinpath('.harness').exists() and not destination.joinpath('跨目录.txt').exists()
            server.shutdown()
    return {'status': 'PASS', 'source': str(source), 'platform': sys.platform, 'localhostRequests': len(requests),
            'checks': ['裸命令通过 PATH 启动', '中文空格路径', '分别从两个项目目录启动，默认使用各自目录', '显式相对 -w 工作区',
                       '旧 YAML workspace 不重定向工作区', '未指定工作区的 resume 从当前目录恢复',
                       '用户默认配置与直接密钥', '重复安装保留配置', '显式相对配置覆盖',
                       'help/doctor 无请求及用法退出码', '凭据不进入输出或会话', '安装目录不被当作工作区']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--result', type=Path)
    args = parser.parse_args()
    report = verify_install(args.source.resolve())
    if args.result:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        args.result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
