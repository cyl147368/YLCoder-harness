"""用 localhost 验证打包/全局 CLI 的三次递增降级重试、脱敏详情、用量和检查点。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == '/v1/chat/completions'
            assert self.headers.get('Authorization') == 'Bearer offline-fixture-key'
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            self.server.requests.append(request)
            self.server.times.append(time.monotonic())
            status = 200
            if len(self.server.requests) == 1:
                assert request.get('stream')
                body = b'data: {"error":{"message":"gateway unavailable offline-fixture-key"}}\n\n'
                media = 'text/event-stream'
                if self.server.failure_kind == 'http400':
                    status, media = 400, 'application/json'
                    body = b'{"error":{"message":"gateway unavailable offline-fixture-key"}}'
            else:
                assert len(self.server.requests) <= 4, '最多自动重试三次'
                assert not request.get('stream')
                assert {k: v for k, v in self.server.requests[0].items() if k != 'stream'} == request
                body = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': '自动重试已完成'},
                                                'finish_reason': 'stop'}],
                                   'usage': {'prompt_tokens': 13, 'completion_tokens': 4}}, ensure_ascii=False).encode('utf-8')
                media = 'application/json'
                if len(self.server.requests) < 4 or self.server.always_fail:
                    status = 400
                    body = b'{"error":{"message":"unsupported parameter offline-fixture-key","code":"invalid_parameter","param":"max_tokens"}}'
            self.send_response(status)
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
        for failure_kind, mode, always_fail in ((kind, mode, fail) for kind in ('sse', 'http400') for mode in ('json', 'interactive') for fail in (False, True)):
            workspace = Path(temporary) / (failure_kind + '-' + mode + ('-failed' if always_fail else '-success'))
            workspace.mkdir()
            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                server.requests, server.errors = [], []
                server.times = []
                server.always_fail = always_fail
                server.failure_kind = failure_kind
                (workspace / 'offline.yaml').write_text(
                    f'provider: openai-compatible\nmodel: fallback-fixture\napi-key: offline-fixture-key\napi-key-env: ""\n'
                    f'base-url: http://127.0.0.1:{server.server_port}/v1\n', encoding='utf-8')
                serving = threading.Thread(target=server.serve_forever, daemon=True)
                serving.start()
                try:
                    arguments = ['--config', 'offline.yaml', '--no-color', '--no-web']
                    if mode == 'json':
                        arguments += ['--json', 'run', 'offline-fixture']
                    environment = os.environ.copy()
                    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                        environment.pop(name, None)
                    completed = subprocess.run(launcher + arguments, cwd=workspace, env=environment,
                                               input='offline-fixture\n/exit\n' if mode == 'interactive' else '',
                                               capture_output=True, encoding='utf-8', timeout=30)
                finally:
                    server.shutdown()
                    serving.join(5)
            assert completed.returncode == (1 if always_fail else 0), completed.stderr
            assert not server.errors, server.errors
            assert len(server.requests) == 4
            gaps = [right - left for left, right in zip(server.times, server.times[1:])]
            assert all(actual >= expected - .05 for actual, expected in zip(gaps, (1, 2, 4))), gaps
            assert 'offline-fixture-key' not in completed.stdout + completed.stderr
            checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
            assert len(checkpoints) == 1
            saved = json.loads(checkpoints[0].read_text(encoding='utf-8'))
            expected_status = 'FAILED' if always_fail else 'COMPLETED'
            assert saved['status'] == expected_status
            assert [row['content'] for row in saved['transcript'] if row['role'] == 'USER'] == ['offline-fixture']
            assert [row['content'] for row in saved['transcript'] if row['role'] == 'ASSISTANT'] == ([] if always_fail else ['自动重试已完成'])
            for stored in (workspace / '.harness').rglob('*'):
                if stored.is_file():
                    assert 'offline-fixture-key' not in stored.read_text(encoding='utf-8')
            if mode == 'json':
                output = json.loads(completed.stdout)
                assert output['status'] == expected_status
                assert output['usageReport']['requests'] == 4
                assert output['usageReport']['inputReportedRequests'] == (0 if always_fail else 1)
                assert output['usageReport']['total']['inputTokens'] == (0 if always_fail else 13)
                if always_fail:
                    assert 'param=max_tokens' in output['error']
                    assert '重试 3 次，仍未成功' in output['error']
                assert not completed.stderr
            else:
                for attempt, delay in enumerate((1, 2, 4), 1):
                    assert f'正在自动改用非流式重试（{attempt}/3），等待 {delay} 秒' in completed.stderr
                assert 'param=max_tokens' in completed.stderr
                assert '4次主模型' in completed.stdout
                assert completed.stdout.count('自动重试已完成') == (0 if always_fail else 1)
            results.append({'failure': failure_kind, 'mode': mode, 'requests': len(server.requests),
                            'checkpoint': saved['status'], 'intervalSeconds': [round(gap, 3) for gap in gaps], 'passed': True})
    report = {'status': 'PASS', 'scope': 'localhost；实际 CLI 进程；无真实厂商调用', 'checks': results}
    options.result.parent.mkdir(parents=True, exist_ok=True)
    options.result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'checks': len(results), 'report': str(options.result)}))


if __name__ == '__main__':
    main()
