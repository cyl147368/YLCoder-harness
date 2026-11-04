"""用真实 CLI 和 localhost 模型夹具验证恢复、回退后的 /model 切换及持久化。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
OLD_MODEL = 'saved-model'
NEW_MODEL = 'deepseek-v4-pro'


def invoke(java_launch, workspace, home, input_text):
    arguments = ['--config', str(workspace / 'fixture.yaml'), '--workspace', str(workspace),
                 '--no-stream', '--no-color', '--quiet']
    if os.name == 'nt':
        argument_file = workspace / 'cli-arguments.txt'
        argument_file.write_text(
            '\n'.join(json.dumps(value, ensure_ascii=False) for value in arguments) + '\n',
            encoding='utf-8')
        arguments = ['@cli-arguments.txt']
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(('JAVA_', 'JDK_JAVA_OPTIONS', 'MAVEN_', 'HARNESS_', 'YLC_'))
                   and key.upper() not in {'CLASSPATH', 'JAVACMD'}}
    environment.update({'HOME': str(home), 'USERPROFILE': str(home),
                        'XDG_CONFIG_HOME': str(home / '.config')})
    return subprocess.run(java_launch + arguments, cwd=workspace, env=environment, input=input_text,
                          capture_output=True, text=True, encoding='utf-8', timeout=60)


def verify(launcher=None):
    if launcher:
        library = Path(launcher).absolute().parent.parent / 'lib'
        runtime_jar = library / (library / 'current-runtime.txt').read_text(encoding='ascii').strip()
        launch = [str(Path(launcher).absolute())]
    else:
        runtime_jar = SOURCE_JAR
        launch = ['java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                  '-Dstderr.encoding=UTF-8', '-jar', str(runtime_jar)]
    if not runtime_jar.is_file():
        raise RuntimeError('缺少可验收的 ylc 运行包')
    requests = []
    with tempfile.TemporaryDirectory(prefix='ylc-model-command-', dir=ROOT / 'target') as temporary:
        base = Path(temporary).resolve()
        workspace = base / 'workspace with spaces'
        workspace.mkdir()
        home = base / 'home'
        home.mkdir()

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(request)
                answer = '本地回复 ' + request['model']
                payload = json.dumps({
                    'choices': [{'message': {'role': 'assistant', 'content': answer}, 'finish_reason': 'stop'}],
                    'usage': {'prompt_tokens': 10, 'completion_tokens': 3},
                }).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            (workspace / 'fixture.yaml').write_text(
                'provider: openai-compatible\n'
                f'model: {OLD_MODEL}\n'
                f'models:\n  - {OLD_MODEL}\n  - deepseek-flash\n  - {NEW_MODEL}\n'
                'discover-models: false\n'
                'api-key: model-command-fixture-key\n'
                f'base-url: http://127.0.0.1:{server.server_port}/v1\n'
                'web:\n  enabled: false\n'
                'permissions:\n  mode: manual\n'
                'trace:\n  enabled: false\n',
                encoding='utf-8')
            first = invoke(launch, workspace, home, '任务一\n任务二\n/exit\n')
            if first.returncode != 0:
                raise AssertionError(f'创建会话失败：{first.stdout}\n{first.stderr}')
            checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
            if len(checkpoints) != 1:
                raise AssertionError('没有生成唯一会话检查点')
            session_id = checkpoints[0].stem
            before_switch = len(requests)
            second = invoke(launch, workspace, home,
                            f'/resume {session_id}\n/rewind 2\n/model\n/model 3\n继续\n/exit\n')
            try:
                server.shutdown()
                worker.join(5)
            finally:
                if not server.socket.fileno() == -1:
                    server.server_close()
        if second.returncode != 0:
            raise AssertionError(f'/model 会话失败：{second.stdout}\n{second.stderr}')
        if '选择模型' not in second.stdout or 'deepseek-flash' not in second.stdout:
            raise AssertionError('模型选择列表没有显示候选：' + second.stdout)
        if '模型已切换为 ' + NEW_MODEL not in second.stdout:
            raise AssertionError(second.stdout)
        if len(requests) - before_switch != 1:
            raise AssertionError(f'切换模型产生了额外请求：{len(requests) - before_switch}')
        switched_request = requests[-1]
        if switched_request['model'] != NEW_MODEL:
            raise AssertionError('下一条任务没有使用新模型')
        serialized = json.dumps(switched_request['messages'], ensure_ascii=False)
        if '任务一' not in serialized or '任务二' in serialized:
            raise AssertionError('回退后的历史不正确')
        session = json.loads(checkpoints[0].read_text(encoding='utf-8'))
        users = [message['content'] for message in session['transcript'] if message['role'] == 'USER']
        if session['model'] != NEW_MODEL or users != ['任务一', '继续']:
            raise AssertionError({'model': session['model'], 'users': users})
        if '[y/N]' in second.stderr:
            raise AssertionError('模型切换不应触发命令审批')
        return {
            'status': 'PASS',
            'completedAtUtc': datetime.now(timezone.utc).isoformat(),
            'launcher': str(Path(launcher).absolute()) if launcher else None,
            'jarSha256': hashlib.sha256(runtime_jar.read_bytes()).hexdigest(),
            'session': session_id,
            'oldModel': OLD_MODEL,
            'newModel': NEW_MODEL,
            'initialRequests': before_switch,
            'postSwitchRequests': len(requests) - before_switch,
            'persistedModel': session['model'],
            'rewoundUsers': users,
            'scope': '真实 CLI 进程；仅 localhost 模型夹具和隔离临时工作区，不调用真实厂商。',
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选全局 ylc 绝对路径')
    parser.add_argument('--result', type=Path, default=ROOT / 'target/model-command-smoke.json')
    options = parser.parse_args()
    if options.launcher and (not options.launcher.is_absolute() or not options.launcher.is_file()):
        raise ValueError('--launcher 必须是现有全局启动器的绝对路径')
    report = verify(options.launcher)
    options.result.parent.mkdir(parents=True, exist_ok=True)
    options.result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"/model 验收通过：{report['oldModel']} -> {report['newModel']}，切换后 1 次任务请求。")
    print('证据：' + str(options.result.resolve()))


if __name__ == '__main__':
    main()
