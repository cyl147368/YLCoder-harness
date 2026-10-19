"""验证打包 CLI 的交互循环：同一进程处理两个任务，再恢复会话。"""
import argparse
import json
import re
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'


class FixtureServer(ThreadingHTTPServer):
    def __exit__(self, *args):
        self.shutdown()
        self.serving.join(5)
        return super().__exit__(*args)


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.server.requests.append(body)
        cycle = len(self.server.requests)
        if getattr(self.server, 'status_case', False):
            if cycle == 1:
                self.send_error(503, 'Service Unavailable', '临时夹具失败')
                return
            response = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': '已恢复后续任务。'},
                                                'finish_reason': 'stop'}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(response)))
            self.end_headers()
            self.wfile.write(response)
            return
        # 交互任务 1 执行一次写入并给出最终响应；任务 2 读取并修改。
        if cycle == 1:
            name, args, text = 'write_file', {'path': 'one.txt', 'content': 'first\n'}, ''
        elif cycle == 2:
            name, args, text = None, None, '第一个任务完成。'
        elif cycle == 3:
            name, args, text = 'read_file', {'path': 'one.txt'}, ''
        elif cycle == 4:
            name, args, text = 'edit_file', {'path': 'one.txt', 'old_text': 'first', 'new_text': 'second'}, ''
        elif cycle == 5:
            name, args, text = None, None, '第二个任务完成。'
        else:
            # 在独立进程中恢复时，必须包含之前两条用户提示与工具结果。
            assert any(m.get('content') == '创建 one.txt。' for m in body['messages'])
            assert any(m.get('content') == '将内容改为 second。' for m in body['messages'])
            assert any(m['role'] == 'tool' and 'second' in m['content'] for m in body['messages'])
            name, args, text = None, None, '已恢复交互会话。'
        message = {'role': 'assistant', 'content': text}
        if name:
            message['tool_calls'] = [{'id': f'interactive-{cycle}', 'type': 'function',
                                      'function': {'name': name, 'arguments': json.dumps(args)}}]
        response = json.dumps({'choices': [{'message': message, 'finish_reason': 'tool_calls' if name else 'stop'}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选：通过指定的启动器验证 UTF-8 管道交互。')
    options = parser.parse_args()
    assert JAR.is_file(), '请先运行 mvn verify'
    with FixtureServer(('127.0.0.1', 0), Provider) as server:
        server.requests = []
        server.serving = threading.Thread(target=server.serve_forever, daemon=True)
        server.serving.start()
        with tempfile.TemporaryDirectory(prefix='harness-interactive-') as temporary:
            workspace = Path(temporary)
            server.workspace = workspace
            config = workspace / 'offline.yaml'
            config.write_text('{}\n', encoding='utf-8')
            launch = [str(options.launcher.resolve())] if options.launcher else ['java', '-jar', str(JAR)]
            common = launch + ['--workspace', str(workspace), '--config', str(config),
                      '--provider', 'openai-compatible', '--model', 'interactive-fixture',
                      '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                      '--api-key-env', 'HARNESS_INTERACTIVE_UNUSED', '--quiet', '--no-color']
            environment = os.environ.copy()
            environment.pop('HARNESS_INTERACTIVE_UNUSED', None)
            empty_workspace = workspace / 'empty'
            empty_workspace.mkdir()
            empty_common = list(common)
            empty_common[empty_common.index('--workspace') + 1] = str(empty_workspace)
            empty = subprocess.run(empty_common, input='/help\n/resume\n/resume 1\n\n/exit\n', capture_output=True,
                                   text=True, encoding='utf-8', env=environment, timeout=15)
            assert empty.returncode == 0, (empty.stdout, empty.stderr)
            assert any(marker in empty.stdout for marker in ('No task has been saved yet.', '尚未保存任何任务。'))
            assert all(marker not in empty.stdout for marker in ('Resume with:', '继续会话：'))
            assert not list((empty_workspace / '.harness/sessions').glob('*.json'))
            assert not server.requests
            assert '当前工作区没有可恢复的会话' in empty.stdout
            assert '序号无效' in empty.stdout
            with subprocess.Popen(common, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding='utf-8', env=environment) as interactive:
                try:
                    interactive.stdin.write('/help\n\n创建 one.txt。\n')
                    interactive.stdin.flush()
                    deadline = time.monotonic() + 20
                    first = None
                    while time.monotonic() < deadline and interactive.poll() is None:
                        checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
                        if checkpoints:
                            try: first = json.loads(checkpoints[0].read_text(encoding='utf-8'))
                            except (OSError, ValueError): pass
                            if first and first['status'] == 'COMPLETED': break
                        time.sleep(0.02)
                    assert first and first['status'] == 'COMPLETED', '下一次输入前，第一个任务未保存检查点'
                    assert [m['content'] for m in first['transcript'] if m['role'] == 'USER'] == ['创建 one.txt。']
                    stdout, stderr = interactive.communicate('/help\n将内容改为 second。\n/status\n/exit\n', timeout=25)
                finally:
                    if interactive.poll() is None:
                        interactive.kill()
                        interactive.communicate(timeout=10)
            assert interactive.returncode == 0, (stdout, stderr)
            assert stdout.count('第一个任务完成。') == 1 and stdout.count('第二个任务完成。') == 1
            assert '› ' in stdout, '交互提示符应使用精简标记'
            for index, region in enumerate(stdout.split('› ')[:-1]):
                footer = '\n'.join(line.strip() for line in region.splitlines() if line.strip())
                footer = '\n'.join(footer.splitlines()[-5:])
                for marker in ('模型', 'interactive-fixture', '厂商', '127.0.0.1', '输入 token',
                               '输出 token', '缓存 token', '缓存命中率'):
                    assert marker in footer, ('第几个提示符', index + 1, '缺少底部状态字段', marker, footer)
            assert '本轮统计' not in stdout + stderr, '交互轮次不能展开旧版大统计面板'
            assert stdout.count('── 进程累计 ──') == 1, '累计详情仅由显式 /status 展开'
            assert any(marker in stdout for marker in ('Enter a coding task.', '请输入编程任务。'))
            assert any(marker + first['id'] in stdout for marker in ('Resume with: ylc resume ', '继续会话：ylc resume '))
            assert re.search(r'^\s*会话\s+' + re.escape(first['id']) + r'（已保存）\s*$',
                             stdout, re.MULTILINE), '/status 必须展示完整已保存会话 ID'
            assert (workspace / 'one.txt').read_text(encoding='utf-8') == 'second\n'
            sessions = list((workspace / '.harness/sessions').glob('*.json'))
            assert len(sessions) == 1
            session = json.loads(sessions[0].read_text(encoding='utf-8'))
            assert session['status'] == 'COMPLETED'
            assert [m['content'] for m in session['transcript'] if m['role'] == 'USER'] == ['创建 one.txt。', '将内容改为 second。']
            assert [m['toolCallId'] for m in session['transcript'] if m['role'] == 'TOOL'] == ['interactive-1', 'interactive-3', 'interactive-4']
            resumed = subprocess.run(common + ['resume', session['id'], '确认第二个值。'],
                                      capture_output=True, text=True, encoding='utf-8', env=environment, timeout=30)
            assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
            assert '已恢复交互会话。' in resumed.stdout
            assert len(list((workspace / '.harness/sessions').glob('*.json'))) == 1
            assert len(server.requests) == 6
            # 交互恢复只载入本地对话，直到用户提交下一项任务才请求模型。
            before = sessions[0].read_bytes()
            selected = subprocess.run(common, input='/resume\n/resume 1\n/status\n/exit\n',
                                      capture_output=True, text=True, encoding='utf-8', env=environment, timeout=20)
            assert selected.returncode == 0, (selected.stdout, selected.stderr)
            assert '已恢复会话：' + session['id'] in selected.stdout
            assert '（已保存）' in selected.stdout
            assert len(server.requests) == 6 and sessions[0].read_bytes() == before
            continued = subprocess.run(common, input=f'/resume {session["id"]}\n继续验证原任务。\n/exit\n',
                                       capture_output=True, text=True, encoding='utf-8', env=environment, timeout=20)
            assert continued.returncode == 0, (continued.stdout, continued.stderr)
            assert '已恢复交互会话。' in continued.stdout
            assert len(server.requests) == 7
            assert server.requests[-1]['messages'][-1]['content'] == '继续验证原任务。'
            assert len(list((workspace / '.harness/sessions').glob('*.json'))) == 1
            # EOF 保留最近任务状态；之后成功的任务可以恢复失败的交互轮次。
            for recover in (False, True):
                server.status_case = True
                server.requests.clear()
                status_workspace = workspace / ('recovered' if recover else 'failed')
                status_workspace.mkdir()
                status_common = list(common)
                status_common[status_common.index('--workspace') + 1] = str(status_workspace)
                lines = 'First request fails.\n' + ('Try another task.\n' if recover else '') + '/status\n'
                status_run = subprocess.run(status_common, input=lines, capture_output=True, text=True,
                                            encoding='utf-8', env=environment, timeout=30)
                assert status_run.returncode == (0 if recover else 1), (status_run.stdout, status_run.stderr)
                saved = list((status_workspace / '.harness/sessions').glob('*.json'))
                assert len(saved) == 1
                checkpoint = json.loads(saved[0].read_text(encoding='utf-8'))
                assert checkpoint['status'] == ('COMPLETED' if recover else 'FAILED')
                assert len(server.requests) == (2 if recover else 1), '意外重试或交互输入丢失'
                assert re.search(r'^\s*会话\s+' + re.escape(checkpoint['id']) + r'（已保存）\s*$',
                                 status_run.stdout, re.MULTILINE), '/status 缺少完整已保存会话标识'
            print(json.dumps({'status': 'PASS', 'modelRequests': 10,
                              'checks': ['空会话帮助与未保存检查点一致', '已保存会话的帮助提供恢复方法',
                                         '帮助与空行不调用模型', '同一交互进程处理两个任务', '每个任务保存检查点',
                                         '独立进程恢复', '交互会话列表、序号和完整 ID 恢复', '仅切换会话不请求模型或改写检查点',
                                         '有序工具历史及最终文件状态',
                                         'EOF 返回最近的状态', '失败轮次后完成成功任务']}, indent=2))


if __name__ == '__main__': main()
