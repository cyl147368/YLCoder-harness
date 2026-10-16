"""通过真实工作区工具，验证打包 CLI 的读取批处理与写入屏障。"""
import json
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
REQUESTS = []
CALLS = [
    ('read-a', 'read_file', {'path': 'A.txt'}),
    ('read-b', 'read_file', {'path': 'B.txt'}),
    ('search', 'search_files', {'query': 'before'}),
    ('write', 'edit_file', {'path': 'A.txt', 'old_text': 'before', 'new_text': 'after'}),
    ('read-after', 'read_file', {'path': 'A.txt'}),
    ('list-after', 'list_files', {}),
]


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        REQUESTS.append(body)
        if not any(m['role'] == 'tool' for m in body['messages']):
            message = {'role': 'assistant', 'content': '', 'tool_calls': [
                {'id': call_id, 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}
                for call_id, name, args in CALLS]}
            finish = 'tool_calls'
        else:
            message = {'role': 'assistant', 'content': '读取及修改屏障已验证。'}
            finish = 'stop'
        encoded = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main():
    assert JAR.is_file(), '请先构建 shaded jar'
    with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            for cap in (1, 4):
                with tempfile.TemporaryDirectory(prefix='harness-parallel-') as temporary:
                    workspace = pathlib.Path(temporary)
                    (workspace / 'A.txt').write_text('before\n', encoding='utf-8')
                    (workspace / 'B.txt').write_text('other\n', encoding='utf-8')
                    common = ['java', '-jar', str(JAR), '--workspace', temporary,
                              '--provider', 'openai-compatible', '--model', 'fixture',
                              '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                              '--api-key-env', 'HARNESS_SMOKE_UNUSED_KEY', '--parallel-reads', str(cap), '--quiet', '--json']
                    run = subprocess.run(common + ['run', '读取文件，修改 A 并验证'],
                                         capture_output=True, text=True, encoding='utf-8', timeout=30)
                    assert run.returncode == 0, (run.stdout, run.stderr)
                    result = json.loads(run.stdout)
                    assert result['status'] == 'COMPLETED', result
                    results = [m for m in REQUESTS[-1]['messages'] if m['role'] == 'tool']
                    assert [m['tool_call_id'] for m in results] == [row[0] for row in CALLS], results
                    assert results[0]['content'] == '1: before\n'
                    assert results[1]['content'] == '1: other\n'
                    assert 'A.txt:1: before' in results[2]['content']
                    assert any(marker in results[3]['content'] for marker in ('replaced 1 literal occurrence', '已替换 1 处字面匹配'))
                    assert results[4]['content'] == '1: after\n'
                    assert 'A.txt' in results[5]['content'] and 'B.txt' in results[5]['content']
                    assert (workspace / 'A.txt').read_text(encoding='utf-8') == 'after\n'
                    saved = json.loads((workspace / '.harness/sessions' / (result['session'] + '.json')).read_text(encoding='utf-8'))
                    assert [m['toolCallId'] for m in saved['transcript'] if m['role'] == 'TOOL'] == [row[0] for row in CALLS]
            print(json.dumps({'status': 'PASS', 'modelRequests': len(REQUESTS), 'parallelReadCaps': [1, 4],
                              'checks': 'actual built-in reads; write barrier; ordered wire and persisted results'}, indent=2))
        finally:
            server.shutdown()


if __name__ == '__main__':
    main()
