"""使用真实 jar 检查 Responses 和 Gemini 的最终回答持久化及回放，仅访问 localhost。"""
import json
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
REQUESTS = []
RESPONSES_ITEMS = [
    {'type': 'reasoning', 'id': 'rs_smoke', 'summary': [], 'encrypted_content': 'opaque+/=='},
    {'type': 'message', 'id': 'msg_smoke', 'role': 'assistant', 'status': 'completed',
     'phase': 'final_answer', 'content': [{'type': 'output_text', 'text': 'First answer', 'annotations': []}]},
]
GEMINI_PARTS = [
    {'text': '私有思考夹具', 'thought': True, 'thoughtSignature': 'thought+/=='},
    {'text': 'First ', 'thoughtSignature': 'text+/=='}, {'text': 'answer'},
]


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        REQUESTS.append((self.path, request))
        if self.path.endswith('/responses'):
            body = {'status': 'completed', 'output': RESPONSES_ITEMS}
        else:
            body = {'candidates': [{'content': {'role': 'model', 'parts': GEMINI_PARTS}, 'finishReason': 'STOP'}]}
        encoded = json.dumps(body).encode()
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
            for provider, key, evidence in (
                ('openai-responses', 'openai.responses.outputItems', RESPONSES_ITEMS),
                ('gemini', 'gemini.contentParts', GEMINI_PARTS),
            ):
                with tempfile.TemporaryDirectory(prefix='harness-message-metadata-') as temporary:
                    common = ['java', '-jar', str(JAR), '--workspace', temporary, '--provider', provider,
                              '--model', 'fixture', '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                              '--api-key-env', 'HARNESS_SMOKE_UNUSED_KEY', '--quiet', '--json']

                    def invoke(arguments, expected=0):
                        result = subprocess.run(common + arguments, capture_output=True, text=True,
                                                encoding='utf-8', timeout=30)
                        assert result.returncode == expected, (result.stdout, result.stderr)
                        assert '私有思考夹具' not in result.stdout
                        return json.loads(result.stdout)

                    start = len(REQUESTS)
                    first = invoke(['run', '给出回答，不修改文件'])
                    assert first['status'] == 'COMPLETED'
                    checkpoint = pathlib.Path(temporary) / '.harness/sessions' / (first['session'] + '.json')
                    saved = json.loads(checkpoint.read_text(encoding='utf-8'))
                    original = saved['transcript'][-1]
                    assert original['content'] == 'First answer'
                    assert json.loads(original['providerMetadata'][key]) == evidence
                    second = invoke(['resume', first['session'], '解释之前的回答'])
                    assert second['status'] == 'COMPLETED'
                    assert len(REQUESTS) == start + 2
                    replay = REQUESTS[-1][1]
                    if provider == 'openai-responses':
                        assert replay['input'][-3:-1] == evidence, replay
                    else:
                        assert replay['contents'][-2] == {'role': 'model', 'parts': evidence}, replay

                    # 将首个检查点恢复为可见投影相互矛盾的内容。
                    # 适配器必须在本地拒绝，而不是发送已篡改的原始回放。
                    original['content'] = 'Tampered answer'
                    checkpoint.write_text(json.dumps(saved), encoding='utf-8')
                    failed = invoke(['resume', first['session'], 'Explain'], expected=1)
                    assert failed['status'] == 'FAILED', failed
                    assert len(REQUESTS) == start + 2, '已篡改回放到达 HTTP'
            print(json.dumps({'status': 'PASS', 'modelRequests': len(REQUESTS),
                              'checks': 'Responses and Gemini final-answer metadata survives separate CLI processes; exact replay; hidden thought; tampering rejected before HTTP'}, indent=2))
        finally:
            server.shutdown()


if __name__ == '__main__':
    main()
