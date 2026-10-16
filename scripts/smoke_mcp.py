"""验证打包 CLI 配置的 MCP 握手、发现、调用及权限分离。"""
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
FIXTURE = '''import json, sys
log = sys.argv[1]
for line in sys.stdin:
    message = json.loads(line)
    with open(log, 'a', encoding='utf-8') as out:
        out.write(message.get('method', '') + '\\n')
    method = message.get('method')
    if 'id' not in message:
        continue
    if method == 'initialize':
        result = {'protocolVersion': '2025-11-25', 'capabilities': {'tools': {}}, 'serverInfo': {'name':'fixture','version':'1'}}
    elif method == 'tools/list':
        result = {'tools': [{'name': 'lookup', 'description': 'Fixture lookup', 'inputSchema': {'type': 'object', 'properties': {'key': {'type': 'string'}}, 'required': ['key']}}]}
    elif method == 'tools/call':
        assert message['params'] == {'name':'lookup','arguments':{'key':'answer'}}
        result = {'content':[{'type':'text','text':'Fixture answer: 42'}], 'structuredContent': {'answer':42}}
    else:
        raise AssertionError(method)
    print(json.dumps({'jsonrpc':'2.0','id':message['id'],'result':result}), flush=True)
'''


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        results = [m.get('content', '') for m in body['messages'] if m['role'] == 'tool']
        if not results:
            names = [t['function']['name'] for t in body['tools'] if t['function']['name'].startswith('mcp_')]
            assert len(names) == 1, names
            message = {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'external-1', 'type': 'function',
                       'function': {'name': names[0], 'arguments': '{"key":"answer"}'}}]}
            finish = 'tool_calls'
        else:
            self.server.results.append(results[-1])
            message = {'role': 'assistant', 'content': 'MCP 冒烟测试完成。'}
            finish = 'stop'
        response = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def main():
    assert JAR.is_file(), '请先运行 mvn verify'
    with tempfile.TemporaryDirectory(prefix='harness-mcp-smoke-') as temporary, ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        workspace = pathlib.Path(temporary)
        fixture, log = workspace / 'fixture.py', workspace / 'rpc.log'
        fixture.write_text(FIXTURE, encoding='utf-8')
        config = workspace / 'config.yaml'
        # JSON 也是合法 YAML，并能精确保留原生 Windows 路径和参数列表。
        config.write_text(json.dumps({'provider': 'openai-compatible', 'model': 'fake',
                                     'api-key-env': 'HARNESS_MCP_SMOKE_UNUSED',
                                     'base-url': f'http://127.0.0.1:{server.server_port}/v1',
                                     'mcp': {'servers': {'fixture': {'command': sys.executable, 'args': [str(fixture), str(log)]}}}}), encoding='utf-8')
        server.results = []
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            common = ['java', '-jar', str(JAR), '--workspace', str(workspace), '--config', str(config), '--quiet', '--json']
            denied = subprocess.run(common + ['--allow-commands', 'run', '使用夹具外部工具。'], capture_output=True, timeout=25)
            assert denied.returncode == 0, (denied.stdout, denied.stderr)
            assert any(marker in server.results[-1].lower() for marker in ('permission denied', '外部 mcp 工具权限被拒绝')), server.results
            assert 'tools/call' not in log.read_text(encoding='utf-8'), 'shell 审批不应授权外部调用'
            allowed = subprocess.run(common + ['--allow-mcp-tools', 'run', '使用夹具外部工具。'], capture_output=True, timeout=25)
            assert allowed.returncode == 0, (allowed.stdout, allowed.stderr)
            assert json.loads(allowed.stdout)['status'] == 'COMPLETED'
            assert 'Fixture answer: 42' in server.results[-1] and any(marker in server.results[-1] for marker in ('Structured result:', '结构化结果：')), server.results
            methods = log.read_text(encoding='utf-8').splitlines()
            assert methods.count('initialize') == 2 and methods.count('notifications/initialized') == 2
            assert methods.count('tools/list') == 2 and methods.count('tools/call') == 1, methods
            print(json.dumps({'status': 'PASS', 'transport': 'stdio', 'initializedServers': 2, 'approvedCalls': 1,
                              'permissionSeparation': 'shell 授权不能授权 MCP 调用'}, indent=2))
        finally:
            server.shutdown()


if __name__ == '__main__':
    main()
