"""仅使用 localhost SSE 服务验证打包 CLI 的流式输出与 JSON 输出。"""
import json
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
release = threading.Event()
requests = []


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()

        def event(payload):
            self.wfile.write(('data: ' + (json.dumps(payload) if isinstance(payload, dict) else payload)
                             + '\n\n').encode())
            self.wfile.flush()

        event({'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'Stream'}}]})
        if not release.wait(10):
            return
        event({'choices': [{'index': 0, 'delta': {'content': ' verified.'}}]})
        event({'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]})
        event({'choices': [], 'usage': {'prompt_tokens': 12, 'completion_tokens': 3,
                                       'total_tokens': 15}})
        event('[DONE]')


def main():
    assert JAR.is_file(), '请先运行 mvn verify'
    with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(prefix='harness-stream-') as workspace:
                config = pathlib.Path(workspace) / 'offline.yaml'
                config.write_text('{}\n', encoding='utf-8')
                common = ['java', '-jar', str(JAR), '--workspace', workspace,
                          '--config', str(config),
                          '--provider', 'langchain4j-openai', '--model', 'mock',
                          '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                          '--api-key-env', 'HARNESS_SMOKE_UNUSED_KEY']
                process = subprocess.Popen(common + ['run', '回复测试短语。'],
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           text=True, encoding='utf-8')
                first = []
                arrived = threading.Event()

                def read_first():
                    first.append(process.stdout.read(1))
                    arrived.set()

                reader = threading.Thread(target=read_first, daemon=True)
                reader.start()
                try:
                    assert arrived.wait(5), '保持完成期间没有输出'
                    assert first == ['S'], first
                    assert process.poll() is None, 'SSE 完成事件之前进程已完成'
                    release.set()
                    reader.join(2)
                    stdout, stderr = process.communicate(timeout=15)
                    stdout = ''.join(first) + stdout
                    assert process.returncode == 0, (stdout, stderr)
                    assert stdout.count('Stream verified.') == 1, stdout
                finally:
                    release.set()
                    if process.poll() is None:
                        process.kill()
                        process.communicate(timeout=5)
                result = subprocess.run(common + ['--json', 'run', '回复测试短语。'],
                                        capture_output=True, text=True, encoding='utf-8', timeout=15)
                assert result.returncode == 0, (result.stdout, result.stderr)
                decoded = json.loads(result.stdout)
                assert decoded['answer'] == 'Stream verified.' and decoded['status'] == 'COMPLETED', decoded
                assert all(request['stream'] for request in requests), requests
                print(json.dumps({'status': 'PASS', 'modelRequests': len(requests),
                                  'checks': '打包程序提前输出文本，最终内容不重复，JSON 干净'}))
        finally:
            release.set()
            server.shutdown()


if __name__ == '__main__':
    main()
