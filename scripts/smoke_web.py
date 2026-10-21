"""离线端到端验证联网工具注册、搜索、缓存、权限边界和关闭开关，仅请求 localhost。"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path)
    parser.add_argument('--result', type=Path)
    options = parser.parse_args()
    reports = []
    for disabled in (False, True):
        state = {'model': 0, 'search': 0, 'errors': []}

        class Server(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, body):
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                try:
                    assert self.path.startswith('/search?format=json&q=')
                    assert not self.headers.get('Authorization') and not self.headers.get('Cookie')
                    state['search'] += 1
                    self.reply({'results': [{'title': 'Official fixture', 'url': 'https://example.com/docs', 'content': 'Verified fixture text'}]})
                except Exception as error:
                    state['errors'].append(repr(error))
                    self.close_connection = True

            def do_POST(self):
                try:
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    state['model'] += 1
                    names = {tool['function']['name'] for tool in body['tools']}
                    assert ('web_search' in names) != disabled and ('web_fetch' in names) != disabled
                    assert '当前本地日期' in body['messages'][0]['content']
                    if not disabled and state['model'] == 1:
                        calls = [ {'id': 'search-' + str(index), 'type': 'function', 'function': {
                            'name': 'web_search', 'arguments': '{"query":"Java documentation"}'}} for index in range(2)]
                        calls.append({'id': 'private-page', 'type': 'function', 'function': {'name': 'web_fetch',
                            'arguments': json.dumps({'url': f'http://127.0.0.1:{self.server.server_port}/search'})}})
                        message, finish = {'role': 'assistant', 'content': '', 'tool_calls': calls}, 'tool_calls'
                    else:
                        if not disabled:
                            outputs = [m['content'] for m in body['messages'] if m['role'] == 'tool']
                            assert len(outputs) == 3 and all('https://example.com/docs' in value for value in outputs[:2]), outputs
                            assert '公网' in outputs[2] or '内网' in outputs[2], outputs
                            assert state['search'] == 1, '相同查询应复用缓存；网页读取不得复用搜索服务的内网例外'
                        message, finish = {'role': 'assistant', 'content': 'web fixture verified'}, 'stop'
                    self.reply({'choices': [{'message': message, 'finish_reason': finish}]})
                except Exception as error:
                    state['errors'].append(repr(error))
                    self.close_connection = True

        with tempfile.TemporaryDirectory(prefix='ylc-web-') as temporary, ThreadingHTTPServer(('127.0.0.1', 0), Server) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            workspace = Path(temporary)
            config = workspace / 'config.yaml'
            config.write_text('provider: openai-compatible\nmodel: fixture\napi-key: LOCAL_MODEL_FIXTURE_SECRET\n'
                f'base-url: http://127.0.0.1:{server.server_port}/v1\nweb:\n  provider: searxng\n'
                f'  search-url: http://127.0.0.1:{server.server_port}/search\n', encoding='utf-8')
            launch = [str(options.launcher.resolve())] if options.launcher else ['java', '-jar', str(JAR)]
            command = [*launch, '--workspace', str(workspace), '--config', str(config), '--no-stream', '--quiet', '--json']
            if disabled:
                command.append('--no-web')
            run = subprocess.run([*command, 'run', 'search the public docs'], capture_output=True, text=True, encoding='utf-8', timeout=30)
            assert run.returncode == 0 and not state['errors'], (run.stdout, run.stderr, state)
            assert json.loads(run.stdout)['status'] == 'COMPLETED'
            assert 'LOCAL_MODEL_FIXTURE_SECRET' not in run.stdout + run.stderr
            assert state['model'] == (1 if disabled else 2) and state['search'] == (0 if disabled else 1), state
            reports.append({'disabled': disabled, 'status': 'PASS', **state})
            server.shutdown()
    result = {'status': 'PASS', 'cases': reports, 'scope': '仅 localhost 模型与搜索夹具'}
    if options.result:
        options.result.parent.mkdir(parents=True, exist_ok=True)
        options.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
