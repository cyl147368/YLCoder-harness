"""通过真实 CLI 和 localhost 模型夹具验证 Git 差异与有限只读链的免确认快速路径。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
TASK = '汇总当前仓库状态并展示最近修改。'
SINGLE = 'git diff --stat'
CHAIN = 'git log --oneline -5 && echo --- && git diff --stat | tail -20 && echo --- && git status --short | wc -l'
DIRECTORY_CHAIN = 'ls -1d */ 2>/dev/null; echo ---; git status --short | head -30; echo ---; git log --oneline -5'
PATH_PREFIX = 'soulclaw-knowledge-server/src/main/java/org/baai/data/soulclaw/knowledge/server/'
RG_SED = ('rg --files soulclaw-knowledge-server/src/main/java'
          + " | sed 's#" + PATH_PREFIX + "##'"
          + ' | head -200')
AI_REVIEWED = 'git show --stat --oneline -1'
STATUS_LOG_RG = ('git status --short; echo \'---\'; git log --oneline -5; echo \'---\';'
                 ' rg --files soulclaw-knowledge-server/src/main/java'
                 ' soulclaw-knowledge-client/src/main/java soulclaw-knowledge-infra/src/main/java | head -200')
LONG_MIXED = ('ls -la; echo \'---\'; wc -c README.md CLAUDE.md MySQL.md; echo \'---\';'
              ' ls soulclaw-knowledge-server soulclaw-knowledge-client soulclaw-knowledge-infra 2>&1 | head -50')


def run_git(root, *arguments):
    git = shutil.which('git')
    if not git:
        raise RuntimeError('验收需要已安装 Git')
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith('GIT_')}
    environment.update({'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                        'GIT_CONFIG_SYSTEM': os.devnull})
    subprocess.run([git, '-c', 'init.templateDir=', *arguments], cwd=root, env=environment,
                   check=True, capture_output=True)


def verify(launcher=None):
    state = {'main': 0, 'review': 0, 'errors': []}
    if launcher:
        library = Path(launcher).absolute().parent.parent / 'lib'
        runtime_jar = library / (library / 'current-runtime.txt').read_text(encoding='ascii').strip()
    else:
        runtime_jar = SOURCE_JAR
    if not runtime_jar.is_file():
        raise RuntimeError('缺少可验收的 ylc 运行包')
    with tempfile.TemporaryDirectory(prefix='ylc-readonly-chain-', dir=ROOT / 'target') as temporary:
        base = Path(temporary).resolve()
        workspace = base / 'workspace with spaces'
        workspace.mkdir()
        home = base / 'home'
        home.mkdir()
        run_git(workspace, 'init', '--quiet')
        (workspace / 'src').mkdir()
        (workspace / 'docs').mkdir()
        (workspace / PATH_PREFIX / 'api').mkdir(parents=True)
        (workspace / PATH_PREFIX / 'api' / 'Controller.java').write_text('not-read\n', encoding='utf-8')
        (workspace / PATH_PREFIX / 'model').mkdir(parents=True)
        (workspace / PATH_PREFIX / 'model' / 'Record.java').write_text('not-read\n', encoding='utf-8')
        (workspace / 'soulclaw-knowledge-client/src/main/java').mkdir(parents=True)
        (workspace / 'soulclaw-knowledge-client/src/main/java/Client.java').write_text('not-read\n', encoding='utf-8')
        (workspace / 'soulclaw-knowledge-infra/src/main/java').mkdir(parents=True)
        (workspace / 'soulclaw-knowledge-infra/src/main/java/Infra.java').write_text('not-read\n', encoding='utf-8')
        (workspace / 'pom.xml').write_text('before\n', encoding='utf-8')
        (workspace / 'README.md').write_text('readme\n', encoding='utf-8')
        (workspace / 'CLAUDE.md').write_text('claude\n', encoding='utf-8')
        (workspace / 'MySQL.md').write_text('mysql\n', encoding='utf-8')
        run_git(workspace, 'add', 'pom.xml')
        run_git(workspace, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '--quiet', '-m', 'fixture')
        (workspace / 'pom.xml').write_text('after\n', encoding='utf-8')

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, status, payload):
                body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    if not request.get('tools'):
                        state['review'] += 1
                        message = {'role': 'assistant', 'content': json.dumps(
                            {'decision': 'approve', 'reason': '只读 Git 摘要'}, ensure_ascii=False)}
                        self.reply(200, {'choices': [{'message': message, 'finish_reason': 'stop'}],
                                         'usage': {'prompt_tokens': 12, 'completion_tokens': 4}})
                        return
                    state['main'] += 1
                    if state['main'] == 1:
                        calls = [
                            {'id': 'git-stat', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': SINGLE})}},
                            {'id': 'git-chain', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': CHAIN})}},
                            {'id': 'directory-chain', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': DIRECTORY_CHAIN})}},
                            {'id': 'rg-sed-chain', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': RG_SED})}},
                            {'id': 'ai-reviewed', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': AI_REVIEWED})}},
                            {'id': 'status-log-rg', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': STATUS_LOG_RG})}},
                            {'id': 'long-mixed', 'type': 'function', 'function': {
                                'name': 'run_command', 'arguments': json.dumps({'command': LONG_MIXED})}},
                        ]
                        message = {'role': 'assistant', 'content': '', 'tool_calls': calls}
                        finish = 'tool_calls'
                    else:
                        outputs = [item['content'] for item in request['messages'] if item.get('role') == 'tool']
                        if len(outputs) != 7 or not all(output.startswith('退出码：0') for output in outputs):
                            raise AssertionError('只读命令未正常执行：' + repr(outputs))
                        if 'pom.xml' not in outputs[0] or '---' not in outputs[1] or 'fixture' not in outputs[1]:
                            raise AssertionError('只读输出缺少预期内容：' + repr(outputs))
                        if 'src' not in outputs[2] or 'docs' not in outputs[2] or 'pom.xml' not in outputs[2]:
                            raise AssertionError('分号/重定向命令链输出缺少预期内容：' + repr(outputs[2]))
                        if 'api/Controller.java' not in outputs[3] or PATH_PREFIX + 'api/Controller.java' in outputs[3]:
                            raise AssertionError('rg/sed/head 前缀裁剪输出不正确：' + repr(outputs[3]))
                        if 'fixture' not in outputs[4]:
                            raise AssertionError('分类器批准的只读命令输出不正确：' + repr(outputs[4]))
                        if '---' not in outputs[5] or 'api/Controller.java' not in outputs[5]:
                            raise AssertionError('长只读摘要链输出不正确：' + repr(outputs[5]))
                        if 'README.md' not in outputs[6] or 'soulclaw-knowledge-infra' not in outputs[6]:
                            raise AssertionError('多目录/多文件长命令输出不正确：' + repr(outputs[6]))
                        message = {'role': 'assistant', 'content': '只读快速路径验收完成。'}
                        finish = 'stop'
                    self.reply(200, {'choices': [{'message': message, 'finish_reason': finish}],
                                     'usage': {'prompt_tokens': 20, 'completion_tokens': 4}})
                except Exception as failure:
                    state['errors'].append(type(failure).__name__ + ': ' + str(failure))
                    self.reply(500, {'error': {'message': 'localhost fixture failed'}})

        with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            config = workspace / 'fixture.yaml'
            config.write_text(
                'provider: openai-compatible\n'
                'model: readonly-chain-fixture\n'
                'api-key: readonly-chain-fixture-key\n'
                f'base-url: http://127.0.0.1:{server.server_port}/v1\n'
                'web:\n  enabled: false\n'
                'permissions:\n  mode: review\n'
                'trace:\n  enabled: false\n',
                encoding='utf-8')
            environment = {key: value for key, value in os.environ.items()
                           if not key.upper().startswith(('JAVA_', 'JDK_JAVA_OPTIONS', 'MAVEN_', 'HARNESS_', 'YLC_'))
                           and key.upper() not in {'CLASSPATH', 'JAVACMD'}}
            environment.update({'HOME': str(home), 'USERPROFILE': str(home),
                                'XDG_CONFIG_HOME': str(home / '.config')})
            if launcher:
                launch = [str(Path(launcher).absolute())]
            else:
                launch = ['java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                          '-Dstderr.encoding=UTF-8', '-jar', str(runtime_jar)]
            arguments = ['--config', str(config), '--workspace', str(workspace),
                         '--no-stream', '--quiet', '--json', 'run', TASK]
            if os.name == 'nt':
                argument_file = workspace / 'cli-arguments.txt'
                argument_file.write_text(
                    '\n'.join(json.dumps(value, ensure_ascii=False) for value in arguments) + '\n',
                    encoding='utf-8')
                arguments = ['@cli-arguments.txt']
            try:
                result = subprocess.run(launch + arguments, cwd=workspace, env=environment,
                                        capture_output=True, text=True, encoding='utf-8', timeout=60)
            finally:
                server.shutdown()
                worker.join(5)
        if result.returncode != 0:
            raise AssertionError(f'CLI 失败：{result.stdout}\n{result.stderr}')
        if state['errors']:
            raise AssertionError(state['errors'])
        if state != {'main': 2, 'review': 1, 'errors': []}:
            raise AssertionError(state)
        if '[y/N]' in result.stderr:
            raise AssertionError('本地快速路径仍请求人工确认')
        report = json.loads(result.stdout)
        if report['status'] != 'COMPLETED' or report['review']['requests'] != 1:
            raise AssertionError(report)
        if report['review']['localApprovals'] != 6:
            raise AssertionError(report['review'])
        return {
            'status': 'PASS',
            'completedAtUtc': datetime.now(timezone.utc).isoformat(),
            'launcher': str(Path(launcher).absolute()) if launcher else None,
            'jar': str(runtime_jar),
            'jarSha256': hashlib.sha256(runtime_jar.read_bytes()).hexdigest(),
            'commands': [SINGLE, CHAIN, DIRECTORY_CHAIN, RG_SED, AI_REVIEWED, STATUS_LOG_RG, LONG_MIXED],
            'modelRequests': state['main'] + state['review'],
            'mainRequests': state['main'],
            'reviewRequests': state['review'],
            'localApprovals': report['review']['localApprovals'],
            'manualPrompts': 0,
            'scope': '真实 CLI 进程；仅 localhost 模型夹具和隔离临时仓库，不调用真实厂商。',
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选全局 ylc 绝对路径')
    parser.add_argument('--result', type=Path,
                        default=ROOT / 'target/readonly-chain-smoke.json')
    options = parser.parse_args()
    if options.launcher and (not options.launcher.is_absolute() or not options.launcher.is_file()):
        raise ValueError('--launcher 必须是现有全局启动器的绝对路径')
    result = verify(options.launcher)
    options.result.parent.mkdir(parents=True, exist_ok=True)
    options.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"只读快速路径验收通过：{result['mainRequests']} 次主模型请求，"
          f"{result['reviewRequests']} 次审核请求，{result['localApprovals']} 次本地放行。")
    print('证据：' + str(options.result.resolve()))


if __name__ == '__main__':
    main()
