"""用 localhost 流式错误验证打包/全局 CLI 的一次自动降级、用量和检查点。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == '/v1/chat/completions'
            assert self.headers.get('Authorization') is None
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            self.server.requests.append(request)
            if len(self.server.requests) == 1:
                assert request.get('stream')
                body = b'data: {"error":{"message":"private-upstream-payload"}}\n\n'
                media = 'text/event-stream'
            else:
                assert len(self.server.requests) == 2, '只能自动重试一次'
                assert not request.get('stream')
                assert {k: v for k, v in self.server.requests[0].items() if k != 'stream'} == request
                body = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': '自动重试已完成'},
                                                'finish_reason': 'stop'}],
                                   'usage': {'prompt_tokens': 13, 'completion_tokens': 4}}, ensure_ascii=False).encode('utf-8')
                media = 'application/json'
            self.send_response(200)
            self.send_header('Content-Type', media)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as failure:
            self.server.errors.append(repr(failure))
            self.send_error(500)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path)
    parser.add_argument('--result', type=Path, default=ROOT / 'target/stream-fallback.json')
    options = parser.parse_args()
    launcher = [str(options.launcher.resolve())] if options.launcher else [
        'java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-jar', str(ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar')]
    results = []
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='stream-fallback-', dir=target) as temporary:
        for mode in ('json', 'interactive'):
            workspace = Path(temporary) / mode
            workspace.mkdir()
            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                server.requests, server.errors = [], []
                (workspace / 'offline.yaml').write_text(
                    f'provider: openai-compatible\nmodel: fallback-fixture\napi-key-env: ""\n'
                    f'base-url: http://127.0.0.1:{server.server_port}/v1\n', encoding='utf-8')
                serving = threading.Thread(target=server.serve_forever, daemon=True)
                serving.start()
                try:
                    arguments = ['--config', 'offline.yaml', '--no-color', '--max-iterations', '1']
                    if mode == 'json':
                        arguments += ['--json', 'run', 'offline-fixture']
                    environment = os.environ.copy()
                    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                        environment.pop(name, None)
                    completed = subprocess.run(launcher + arguments, cwd=workspace, env=environment,
                                               input='offline-fixture\n/exit\n' if mode == 'interactive' else '',
                                               capture_output=True, encoding='utf-8', timeout=20)
                finally:
                    server.shutdown()
                    serving.join(5)
            assert completed.returncode == 0, completed.stderr
            assert not server.errors, server.errors
            assert len(server.requests) == 2
            assert 'private-upstream-payload' not in completed.stdout + completed.stderr
            checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
            assert len(checkpoints) == 1
            saved = json.loads(checkpoints[0].read_text(encoding='utf-8'))
            assert saved['status'] == 'COMPLETED'
            assert [row['content'] for row in saved['transcript'] if row['role'] == 'USER'] == ['offline-fixture']
            assert [row['content'] for row in saved['transcript'] if row['role'] == 'ASSISTANT'] == ['自动重试已完成']
            if mode == 'json':
                output = json.loads(completed.stdout)
                assert output['status'] == 'COMPLETED'
                assert output['usageReport']['requests'] == 2
                assert output['usageReport']['inputReportedRequests'] == 1
                assert output['usageReport']['total']['inputTokens'] == 13
                assert not completed.stderr
            else:
                assert '正在自动改用非流式重试（1/1）' in completed.stderr
                assert '2次主模型' in completed.stdout
                assert completed.stdout.count('自动重试已完成') == 1
            results.append({'mode': mode, 'requests': len(server.requests), 'checkpoint': saved['status'], 'passed': True})
    report = {'status': 'PASS', 'scope': 'localhost；实际 CLI 进程；无真实厂商调用', 'checks': results}
    options.result.parent.mkdir(parents=True, exist_ok=True)
    options.result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'checks': len(results), 'report': str(options.result)}))


if __name__ == '__main__':
    main()
