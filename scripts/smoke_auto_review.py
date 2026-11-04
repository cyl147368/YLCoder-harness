"""使用 localhost 验证真实 CLI 的审核子 agent、用量、缓存和失败回退。"""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import shutil
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
PRIVATE_TASK = '检查当前工作区的只读信息；保留 "带空格的文字" 和 C:\\中文目录\\space name\\file.txt。'
ASSISTANT_PROSE = 'ASSISTANT_FREE_TEXT_MUST_NOT_ENTER_REVIEW'
REVIEW_REASON = 'RAW_REVIEW_REASON_MUST_NOT_BE_DISPLAYED'
SAFE_COMMAND = 'dir /b' if os.name == 'nt' else 'pwd'
DATE_COMMAND = "date '+%Y-%m-%d %H:%M:%S %Z (%z)'"
JAVA_FILES_COMMAND = "rg --files -g AGENTS.md -g pom.xml -g '*Test*' -g '*Application.java' -g '*Controller.java' -g '*ServiceImpl.java' -g '*Security*' -g '*ExceptionHandler*' -g 'application*.yml' -g 'application*.yaml' -g '.github/**' -g '!target'"
FIND_COMMAND = "find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'"
WHERE_COMMANDS = {
    'where-native': 'where /r . AGENTS.md',
    'where-uppercase': 'WHERE /R . agents.md',
    'where-exe-quoted': 'WhErE.ExE    /R    ".\\"    "AGENTS.md"',
    'where-multiple': "where /r './' AGENTS.md 'README.md' '*.java'",
    'where-empty': "where /r . '*ExceptionHandler*'",
    'where-parent': 'where /r .. AGENTS.md',
    'where-chain': 'where /r . AGENTS.md & echo WHERE_SHOULD_NOT_EXECUTE > blocked.txt',
}
WHERE_DENIED = {'where-parent', 'where-chain'}
WHERE_FILE_CONTENT = 'WHERE_FILE_BODY_MUST_NOT_APPEAR_IN_QUERY_OUTPUT'
SCOPED_SEARCH_ROOTS = (
    'soulclaw-knowledge-server/src/main/java', 'soulclaw-knowledge-server/src/test/java',
    'soulclaw-knowledge-client/src/main/java', 'soulclaw-knowledge-infra/src/main/java',
)
SCOPED_SEARCH_COMMANDS = {
    'rg-scoped-files': 'rg --files soulclaw-knowledge-server/src/main/java soulclaw-knowledge-server/src/test/java soulclaw-knowledge-client/src/main/java soulclaw-knowledge-infra/src/main/java | head -200',
    'rg-scoped-content': 'rg -n "@RestController|@Service|@Repository|@Scheduled|@XxlJob|CompletableFuture|ThreadPoolTaskExecutor|Redis|Elasticsearch|RestClient|S3|Minio" soulclaw-knowledge-server/src/main/java soulclaw-knowledge-infra/src/main/java | head -240',
    'rg-spaced-head': 'rg --files "module space/src" | head -n 2',
    'rg-content-literal': 'rg -n -i -F -e "redis|literal" --glob "*.custom" "module space/src" | head --lines=2',
    'rg-custom-globs': 'rg --files --glob "**/foo?.custom" --glob "!**/skip/**" "module space/src" | head -20',
    'rg-scoped-parent': 'rg --files ../ | head -200',
    'rg-scoped-chain': 'rg --files module | head -1 && echo SEARCH_SHOULD_NOT_EXECUTE > blocked.txt',
    'rg-scoped-redirect': 'rg --files module > blocked.txt',
    'rg-scoped-extra-pipe': 'rg --files module | head -1 | echo SEARCH_SHOULD_NOT_EXECUTE > blocked.txt',
}
SCOPED_SEARCH_DENIED = {'rg-scoped-parent', 'rg-scoped-chain', 'rg-scoped-redirect', 'rg-scoped-extra-pipe'}
SCOPED_SEARCH_ALLOWED = set(SCOPED_SEARCH_COMMANDS) - SCOPED_SEARCH_DENIED
TIME_COMMAND = ('powershell -NoProfile -Command "Get-Date -Format \'yyyy-MM-dd HH:mm:ss zzz\'"'
                if os.name == 'nt' else "date '+%Y-%m-%d %H:%M:%S %z'")


def prepare_scoped_search(workspace, case):
    """构造有明确范围和计数的查询样本，避免只验证退出码而漏掉过度遍历。"""
    expected = set()

    def write(name, content):
        path = workspace / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')

    # 即使另一个模块也命中模式，也不得出现在指定目录的查询结果中。
    write('outside-module/Outside.java', '@Service OUTSIDE_SCOPE_MUST_NOT_APPEAR\n')
    write('outside-module/foo3.custom', 'Redis|literal OUTSIDE_SCOPE_MUST_NOT_APPEAR\n')
    write('module/One.java', '@Service\n')
    for command_name in ('rg', 'head'):
        write(command_name + '.cmd', '@echo SEARCH_SHOULD_NOT_EXECUTE>search-executed.txt\n')

    if case == 'rg-scoped-files':
        # 原样复现用户的多目录写法；少量结果避免先触发独立的输出字符预算。
        for root in SCOPED_SEARCH_ROOTS:
            for index in range(3):
                name = f'{root}/Example{index:03}.java'
                write(name, 'FILE_CONTENT_MUST_NOT_BE_READ\n')
                expected.add(name)
            write(f'{root}/.hidden/Hidden.java', 'HIDDEN_SEARCH_MUST_NOT_APPEAR\n')
        return expected, len(expected)
    if case == 'rg-scoped-content':
        # 正则中的引号和 | 必须保留；精确的 head 截断另用短路径样本验证。
        words = ('@RestController', '@Service', '@Repository', '@Scheduled', '@XxlJob',
                 'CompletableFuture', 'ThreadPoolTaskExecutor', 'Redis', 'Elasticsearch',
                 'RestClient', 'S3', 'Minio')
        for root in (SCOPED_SEARCH_ROOTS[0], SCOPED_SEARCH_ROOTS[3]):
            name = f'{root}/Example.java'
            lines = [f'{word} MATCH_{index:03}' for index, word in enumerate(words)]
            write(name, '\n'.join(lines) + '\n')
            expected.update(f'{name}:{index + 1}: {line}' for index, line in enumerate(lines))
            write(f'{root}/.hidden/Hidden.java', '@Service HIDDEN_SEARCH_MUST_NOT_APPEAR\n')
            write(f'{root}/credentials.json', '@Service SENSITIVE_SEARCH_MUST_NOT_APPEAR\n')
            (workspace / root / 'binary.bin').write_bytes(b'@Service BINARY_SEARCH_MUST_NOT_APPEAR\x00\n')
        return expected, len(expected)
    if case == 'rg-spaced-head':
        for index in range(5):
            name = f'module space/src/Example{index}.java'
            write(name, 'FILE_CONTENT_MUST_NOT_BE_READ\n')
            expected.add(name)
        return expected, 2
    if case == 'rg-content-literal':
        name = 'module space/src/Read.custom'
        lines = ('Redis|literal ONE', 'REDIS|LITERAL TWO', 'redis|literal THREE', 'RedisXliteral NOT_LITERAL')
        write(name, '\n'.join(lines) + '\n')
        write('module space/src/Excluded.java', 'Redis|literal WRONG_GLOB_MUST_NOT_APPEAR\n')
        expected.update(f'{name}:{index + 1}: {line}' for index, line in enumerate(lines[:3]))
        return expected, 2
    if case == 'rg-custom-globs':
        for name in ('module space/src/main/foo1.custom', 'module space/src/test/foo2.custom'):
            write(name, 'FILE_CONTENT_MUST_NOT_BE_READ\n')
            expected.add(name)
        for name in ('module space/src/skip/foo3.custom', 'module space/src/main/foo12.custom',
                     'module space/src/main/bar1.custom', 'module space/src/main/foo1.java'):
            write(name, 'FILE_CONTENT_MUST_NOT_BE_READ\n')
        return expected, 2
    return expected, 0


def verify_scoped_search_output(case, output, expected, expected_count):
    """计数只包括结果行，中文查询提示和退出码不属于 head 的内容预算。"""
    lines = output.splitlines()
    actual = [line for line in lines if line.startswith(('soulclaw-', 'module space/', 'outside-module/'))]
    assert len(actual) == expected_count and len(set(actual)) == expected_count, (case, actual)
    assert set(actual) <= expected, (case, set(actual) - expected)
    for marker in ('OUTSIDE_SCOPE_MUST_NOT_APPEAR', 'HIDDEN_SEARCH_MUST_NOT_APPEAR',
                   'SENSITIVE_SEARCH_MUST_NOT_APPEAR', 'BINARY_SEARCH_MUST_NOT_APPEAR',
                   'FILE_CONTENT_MUST_NOT_BE_READ', 'WRONG_GLOB_MUST_NOT_APPEAR', 'NOT_LITERAL'):
        assert marker not in output, (case, output)


def verify_review(launcher=None):
    records = []
    with tempfile.TemporaryDirectory(prefix='ylc review 中文 ') as temporary:
        base = Path(temporary)
        for case in ('approved-cache', 'manual', 'malformed', 'http-error', 'disabled', 'high-risk', 'all-allowed',
                     'git-status-approved', 'git-status-manual', 'git-status-filter',
                     'git-diff-paths', 'git-diff-staged', 'git-diff-filter', 'git-diff-output',
                     'time-query-approved', 'date-query-approved', 'time-query-manual', 'time-injection',
                     'git-clean-preview', 'git-clean-delete', 'rg-files-approved', 'rg-filtered-files',
                     'native-fastpath', 'find-native', 'rg-java-files', 'rg-java-long', *WHERE_COMMANDS,
                     *SCOPED_SEARCH_COMMANDS):
            workspace = base / case
            workspace.mkdir()
            marker = workspace / 'marker.txt'
            marker.write_text('fixture\n', encoding='utf-8')
            nested = workspace / 'nested'
            nested.mkdir()
            (nested / 'SHOULD_NOT_LIST_RECURSIVELY.txt').write_text('fixture', encoding='utf-8')
            config = workspace / 'offline.yaml'
            config.write_text('{}\n', encoding='utf-8')
            state = {'main': [], 'review': [], 'errors': []}
            command = 'echo AUTO_REVIEW_BLOCKED > blocked.txt' if case == 'high-risk' else SAFE_COMMAND
            if case in ('approved-cache', 'manual', 'malformed', 'http-error'):
                command = DATE_COMMAND
            elif case.startswith('time-query-'):
                command = TIME_COMMAND
            elif case == 'date-query-approved':
                command = DATE_COMMAND
            elif case == 'time-injection':
                command = TIME_COMMAND + ' > blocked.txt'
            elif case == 'git-clean-preview':
                command = 'git clean -nd'
            elif case == 'git-clean-delete':
                command = 'git clean -fd'
            elif case in ('rg-files-approved', 'rg-filtered-files', 'find-native', 'rg-java-files', 'rg-java-long'):
                command = ("rg --files --hidden -g AGENTS.md -g '!.git'" if case == 'rg-files-approved' else
                           "rg --files -g AGENTS.md -g README.md -g '*.java' -g pom.xml -g '*test*' -g '*Test*' -g '.github/**'"
                           if case == 'rg-filtered-files' else FIND_COMMAND)
                if case in ('rg-java-files', 'rg-java-long'):
                    command = JAVA_FILES_COMMAND if case == 'rg-java-files' else JAVA_FILES_COMMAND.replace(' -g ', ' --glob ')
                    for name in ('src/DemoApplication.java', 'src/UserController.java', 'src/UserServiceImpl.java',
                                 'src/GlobalExceptionHandler.java', 'src/SecurityConfig.java', 'config/application-dev.yml',
                                 'config/application.yaml', 'target/ExcludedTest.java', 'nested/target/ExcludedTest.java'):
                        path = workspace / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text('never read fixture contents', encoding='utf-8')
                (workspace / 'AGENTS.md').write_text('fixture instruction', encoding='utf-8')
                for name in ('README.md', 'pom.xml', 'src/Main.java', 'src/ExampleTest.java',
                             'docs/test-guide.md', '.github/workflows/build.yml'):
                    path = workspace / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('never read fixture contents', encoding='utf-8')
                (nested / 'AGENTS.md').write_text('nested fixture instruction', encoding='utf-8')
                for excluded in ('.git', '.harness'):
                    (workspace / excluded).mkdir(exist_ok=True)
                    (workspace / excluded / 'AGENTS.md').write_text('excluded fixture instruction', encoding='utf-8')
            if case in WHERE_COMMANDS:
                command = WHERE_COMMANDS[case]
                for name in ('AGENTS.md', 'nested/agents.md', 'README.md', 'src/Main.JAVA',
                             '.git/AGENTS.md', '.harness/AGENTS.md', 'AGENTS.md.exe'):
                    path = workspace / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(WHERE_FILE_CONTENT, encoding='utf-8')
                # Windows shell 若误执行当前目录中的同名命令，会留下独立的副作用标记。
                (workspace / 'where.cmd').write_text('@echo WHERE_SHOULD_NOT_EXECUTE>where-executed.txt\n', encoding='utf-8')
            if case in SCOPED_SEARCH_COMMANDS:
                command = SCOPED_SEARCH_COMMANDS[case]
                search_expected, search_count = prepare_scoped_search(workspace, case)
            repeated = case in ('approved-cache', 'git-status-approved', 'date-query-approved')
            if case.startswith('git-status-'):
                command = 'git status --short --branch --untracked-files=all' if case == 'git-status-approved' else 'git status --short'
            if case.startswith('git-'):
                git = shutil.which('git')
                assert git, 'Git 状态审核验收需要已安装的 Git'
                git_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('GIT_')}
                git_env.update({'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_SYSTEM': os.devnull})
                subprocess.run([git, '-c', 'init.templateDir=', 'init', '--quiet'], cwd=workspace, env=git_env, check=True, capture_output=True)
                subprocess.run([git, 'config', 'core.fsmonitor', 'echo blocked > blocked.txt'],
                               cwd=workspace, env=git_env, check=True, capture_output=True)
                if case == 'git-status-filter':
                    subprocess.run([git, 'config', 'filter.fixture.clean', 'echo blocked > blocked.txt'],
                                   cwd=workspace, env=git_env, check=True, capture_output=True)
                if case.startswith('git-diff-'):
                    names = ('build.sh', 'pom.xml', 'soulclaw-knowledge-server/src/main/resources/application.yml')
                    for name in names:
                        file = workspace / name
                        file.parent.mkdir(parents=True, exist_ok=True)
                        file.write_text('before\n', encoding='utf-8')
                    subprocess.run([git, '-c', 'core.fsmonitor=false', 'add', '--', *names], cwd=workspace,
                                   env=git_env, check=True, capture_output=True)
                    for name in names:
                        (workspace / name).write_text('after\n', encoding='utf-8')
                    command = 'git diff -- ' + ' '.join(names)
                    if case == 'git-diff-staged':
                        command = 'git diff --cached -- ' + ' '.join(names)
                    elif case == 'git-diff-filter':
                        subprocess.run([git, 'config', 'filter.fixture.clean', 'echo blocked > blocked.txt'],
                                       cwd=workspace, env=git_env, check=True, capture_output=True)
                    elif case == 'git-diff-output':
                        command = 'git diff --output=blocked.txt'
                    subprocess.run([git, 'config', 'diff.external', 'echo blocked > blocked.txt'],
                                   cwd=workspace, env=git_env, check=True, capture_output=True)

            class Provider(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_POST(self):
                    try:
                        assert self.path == '/v1/chat/completions'
                        assert 'Authorization' not in self.headers
                        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                        review = not body.get('tools')
                        state['review' if review else 'main'].append(body)
                        if not review:
                            assert [m['content'] for m in body['messages'] if m['role'] == 'user'] == [PRIVATE_TASK], '中文主任务或参数引用发生变化'
                        if review:
                            assert body.get('stream') is not True
                            assert body['max_tokens'] == 192
                            assert len(body['messages']) == 2
                            data = json.loads(body['messages'][-1]['content'])
                            assert data['command'] == command
                            assert any(item.get('role') == 'user' and item.get('content') == PRIVATE_TASK
                                       for item in data['context']), '审核应收到有界的真实用户任务投影'
                            assert ASSISTANT_PROSE not in json.dumps(body, ensure_ascii=False), '助手自由文本不能影响审核'
                            assert 'fixture\n' not in json.dumps(body, ensure_ascii=False)
                            if case == 'http-error':
                                self.send_response(503)
                                self.end_headers()
                                self.wfile.write(b'DO_NOT_LOG_REVIEW_SECRET')
                                return
                            decision = 'manual' if case in ('manual', 'git-status-manual', 'time-query-manual') else 'approve'
                            text = json.dumps({'decision': decision, 'reason': REVIEW_REASON})
                            if case == 'malformed':
                                text += ' trailing'
                            message = {'role': 'assistant', 'content': text}
                        elif len(state['main']) == 1:
                            message = {'role': 'assistant', 'content': ASSISTANT_PROSE, 'tool_calls': [
                                {'id': f'auto-review-{index}', 'type': 'function', 'function': {
                                    'name': 'run_command', 'arguments': json.dumps({'command': command})}}
                                for index in range(2 if repeated else 1)]}
                        else:
                            assert len(state['main']) == 2
                            outputs = [m['content'] for m in body['messages'] if m['role'] == 'tool']
                            assert len(outputs) == (2 if repeated else 1)
                            approved = case in ('approved-cache', 'all-allowed', 'git-status-approved', 'git-status-manual',
                                                'time-query-approved', 'date-query-approved', 'git-clean-preview',
                                                'rg-files-approved', 'rg-filtered-files', 'native-fastpath', 'find-native', 'rg-java-files', 'rg-java-long',
                                                'git-diff-paths', 'git-diff-staged') or case in WHERE_COMMANDS and case not in WHERE_DENIED or case in SCOPED_SEARCH_ALLOWED
                            assert all(('退出码：0' if approved else '未获批准') in output for output in outputs), outputs
                            if case in ('time-query-approved', 'date-query-approved'):
                                assert all(re.search(r'\b\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\b', output)
                                           for output in outputs), outputs
                                assert all(re.search(r'[+-]\d{2}:?\d{2}', output) for output in outputs), outputs
                            if case == 'native-fastpath' and os.name == 'nt':
                                assert all('SHOULD_NOT_LIST_RECURSIVELY' not in output for output in outputs)
                            if case == 'git-clean-preview':
                                assert all('marker.txt' in output and '.harness' not in output for output in outputs), outputs
                            if case in ('git-diff-paths', 'git-diff-staged'):
                                assert all(all(name in output for name in names) for output in outputs), outputs
                                assert all(('+before' if case == 'git-diff-staged' else '+after') in output for output in outputs), outputs
                            if case in ('rg-files-approved', 'rg-filtered-files', 'find-native', 'rg-java-files', 'rg-java-long'):
                                for output in outputs:
                                    paths = output.splitlines()
                                    assert 'AGENTS.md' in paths and 'nested/AGENTS.md' in paths, paths
                                    assert not any(path.startswith(('.harness/', '.git/')) for path in paths), paths
                                if case == 'rg-files-approved':
                                    assert all('不读取 .gitignore/.ignore' in output for output in outputs), outputs
                                if case == 'rg-filtered-files':
                                    expected_paths = ('README.md', 'pom.xml', 'src/Main.java', 'src/ExampleTest.java',
                                                      'docs/test-guide.md', '.github/workflows/build.yml')
                                    assert all(all(path in output.splitlines() for path in expected_paths) for output in outputs), outputs
                                    assert all(not any(path.startswith(('.git/', '.harness/')) for path in output.splitlines())
                                               for output in outputs), outputs
                            if case in ('rg-java-files', 'rg-java-long'):
                                included = ('AGENTS.md', 'pom.xml', 'src/ExampleTest.java', 'src/DemoApplication.java',
                                            'src/UserController.java', 'src/UserServiceImpl.java', 'src/GlobalExceptionHandler.java',
                                            'src/SecurityConfig.java', 'config/application-dev.yml', 'config/application.yaml',
                                            '.github/workflows/build.yml')
                                for output in outputs:
                                    assert all(path in output.splitlines() for path in included), output
                                    assert 'ExcludedTest.java' not in output and 'never read fixture contents' not in output, output
                                    assert 'src/Main.java' not in output and 'README.md' not in output, output
                            if case in WHERE_COMMANDS and case not in WHERE_DENIED:
                                for output in outputs:
                                    paths = output.splitlines()
                                    assert '原生 where 文件名查询' in output, output
                                    assert '不展开 PATH/PATHEXT' in output, output
                                    assert WHERE_FILE_CONTENT not in output, output
                                    assert not any(str(workspace / excluded / 'AGENTS.md') in paths
                                                   for excluded in ('.git', '.harness')), output
                                    assert str(workspace / 'AGENTS.md.exe') not in paths, output
                                    if case == 'where-empty':
                                        assert '[未找到匹配文件]' in paths, output
                                        assert not any(Path(path).is_absolute() for path in paths), output
                                    else:
                                        expected_paths = ['AGENTS.md', 'nested/agents.md']
                                        if case == 'where-multiple':
                                            expected_paths += ['README.md', 'src/Main.JAVA']
                                        actual_paths = [path for path in paths if Path(path).is_absolute()]
                                        assert set(actual_paths) == {str(workspace / path) for path in expected_paths}, output
                                        assert '[未找到匹配文件]' not in paths, output
                            if case in SCOPED_SEARCH_ALLOWED:
                                for output in outputs:
                                    verify_scoped_search_output(case, output, search_expected, search_count)
                            message = {'role': 'assistant', 'content': '本地审核验收完成。'}
                        response = json.dumps({'choices': [{'message': message,
                            'finish_reason': 'tool_calls' if message.get('tool_calls') else 'stop'}],
                            'usage': {'prompt_tokens': 50 if review else 13,
                                      'completion_tokens': 7 if review else 3}}).encode('utf-8')
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(response)))
                        self.end_headers()
                        self.wfile.write(response)
                    except Exception as failure:
                        state['errors'].append(repr(failure))
                        self.send_response(500)
                        self.end_headers()

            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                environment = os.environ.copy()
                environment.pop('HARNESS_AUTO_REVIEW_UNUSED', None)
                environment['DIRCMD'] = '/s'
                launch = [str(Path(launcher).resolve())] if launcher else [
                    'java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                    '-Dstderr.encoding=UTF-8', '-jar', str(JAR)]
                arguments = ['--config', str(config), '--workspace', str(workspace),
                    '--provider', 'openai-compatible', '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                    '--model', 'auto-review-fixture', '--api-key-env', 'HARNESS_AUTO_REVIEW_UNUSED',
                    '--no-stream', '--quiet', '--json']
                if case in ('rg-java-files', 'rg-java-long') or case in WHERE_COMMANDS or case in SCOPED_SEARCH_COMMANDS:
                    arguments += ['--approval-mode', 'review']
                if case == 'disabled':
                    arguments.append('--no-auto-review')
                if case == 'all-allowed':
                    arguments += ['--allow-commands', '--no-auto-review']
                arguments += ['run', PRIVATE_TASK]
                if os.name == 'nt':
                    # Java 21 的原生 Windows 参数先经过 ACP；应用级参数文件保留中文。
                    # 文件名保持相对 ASCII，内容仍覆盖真实中文路径、空格、引号和反斜线。
                    argument_file = workspace / 'cli-arguments.txt'
                    argument_file.write_text('\n'.join(json.dumps(value, ensure_ascii=False) for value in arguments) + '\n', encoding='utf-8')
                    arguments = ['@' + argument_file.name]
                try:
                    run = subprocess.run(launch + arguments, input='', capture_output=True,
                        text=True, encoding='utf-8', env=environment, cwd=workspace, timeout=35)
                finally:
                    server.shutdown()
                    thread.join(5)
            assert run.returncode == 0, (case, run.stdout, run.stderr, state['errors'])
            assert not state['errors'], (case, state['errors'])
            result = json.loads(run.stdout)
            assert result['status'] == 'COMPLETED' and result['iterations'] == 2, (case, result)
            assert 'DO_NOT_LOG_REVIEW_SECRET' not in run.stdout + run.stderr
            assert REVIEW_REASON not in run.stdout + run.stderr
            assert '[y/N]' not in run.stderr, '管道模式不能读取确认回答'
            assert not (workspace / 'blocked.txt').exists()
            assert not (workspace / 'where-executed.txt').exists(), '文件名查询不得启动工作区的 where.cmd'
            assert not (workspace / 'search-executed.txt').exists(), '原生查询不得启动工作区的 rg.cmd 或 head.cmd'
            assert marker.read_text(encoding='utf-8') == 'fixture\n'
            expected_reviews = int(case in ('approved-cache', 'manual', 'malformed', 'http-error',
                                             'time-query-approved', 'date-query-approved', 'time-query-manual', 'git-clean-preview'))
            expected_local = 2 if case == 'git-status-approved' else int(case in (
                'git-status-manual', 'rg-files-approved', 'rg-filtered-files', 'native-fastpath', 'find-native', 'rg-java-files', 'rg-java-long',
                'git-diff-paths', 'git-diff-staged') or case in WHERE_COMMANDS and case not in WHERE_DENIED or case in SCOPED_SEARCH_ALLOWED)
            expected_cached = int(case in ('approved-cache', 'date-query-approved'))
            expected_ai_approved = 2 if expected_cached else int(case in ('time-query-approved', 'git-clean-preview'))
            assert len(state['main']) == 2 and len(state['review']) == expected_reviews, (case, state)
            review = result['review']
            assert review['requests'] == expected_reviews
            assert review['cacheHits'] == expected_cached, (case, review)
            assert review['localApprovals'] == expected_local, (case, review)
            assert review['approved'] == expected_local + expected_ai_approved, (case, review)
            assert review['manual'] == int(case in ('manual', 'malformed', 'http-error', 'high-risk',
                                                     'git-status-filter', 'time-query-manual', 'time-injection', 'git-clean-delete',
                                                     'git-diff-filter', 'git-diff-output') or case in WHERE_DENIED or case in SCOPED_SEARCH_DENIED), (case, review)
            assert review['elapsedMillis'] >= 0, (case, review)
            if expected_reviews == 0:
                assert review['elapsedMillis'] == 0, (case, review)
            assert review['unreportedRequests'] == int(case == 'http-error')
            known = expected_reviews and case != 'http-error'
            assert result['usage']['inputTokens'] == 26 + (50 if known else 0), (case, result)
            assert result['usage']['outputTokens'] == 6 + (7 if known else 0)
            assert result['agentUsage']['inputTokens'] == 26 and result['agentUsage']['outputTokens'] == 6
            session = json.loads((workspace / '.harness/sessions' / (result['session'] + '.json')).read_text(encoding='utf-8'))
            assert Path(session['workspace']).samefile(workspace), '会话必须使用实际中文工作区'
            assert [m['content'] for m in session['transcript'] if m['role'] == 'USER'] == [PRIVATE_TASK]
            assert REVIEW_REASON not in json.dumps(session, ensure_ascii=False), '审核原始理由不能写入主会话'
            records.append({'case': case, 'mainRequests': len(state['main']), 'reviewRequests': expected_reviews,
                            'review': review, 'status': 'PASS'})
    return {'status': 'PASS', 'localhostRequests': sum(x['mainRequests'] + x['reviewRequests'] for x in records),
            'cases': records, 'scope': '仅本地模拟厂商；不请求真实模型或测量线上质量/延迟。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path)
    parser.add_argument('--result', type=Path)
    options = parser.parse_args()
    result = verify_review(options.launcher)
    if options.result:
        options.result.parent.mkdir(parents=True, exist_ok=True)
        options.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
