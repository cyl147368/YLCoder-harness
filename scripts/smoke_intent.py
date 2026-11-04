"""使用真实启动器和本地替身验证意图分流；不读取私有配置或调用真实厂商。"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from smoke_model_command import ROOT, SOURCE_JAR, invoke


def verify(launcher=None):
    jar = SOURCE_JAR
    if launcher:
        library = Path(launcher).absolute().parent.parent / 'lib'
        jar = library / (library / 'current-runtime.txt').read_text(encoding='ascii').strip()
    launch = [str(Path(launcher).absolute())] if launcher else ['java', '-Dfile.encoding=UTF-8', '-jar', str(jar)]
    requests = []
    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            body = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': '本地完成'}, 'finish_reason': 'stop'}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    with tempfile.TemporaryDirectory(prefix='intent-', dir=ROOT / 'target') as temp:
        workspace = Path(temp)
        home = workspace / 'home'
        home.mkdir()
        with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            (workspace / 'fixture.yaml').write_text('provider: openai-compatible\nmodel: fixture\napi-key: local-fixture\nbase-url: http://127.0.0.1:' + str(server.server_port) + '/v1\nweb:\n  enabled: false\ntrace:\n  enabled: false\n', encoding='utf-8')
            result = invoke(launch, workspace, home, '/intent\n你好\n先给我修复方案\n继续\n可以开始实现\n/intent analyze\n任务\n/exit\n')
            assert result.returncode == 0, result.stdout + result.stderr
            assert len(requests) == 5, len(requests)
            schemas = [r.get('tools', []) for r in requests]
            names = [[t['function']['name'] for t in s] for s in schemas]
            assert not names[0]
            for i in [1, 2, 4]:
                assert 'read_file' in names[i] and 'write_file' not in names[i] and 'run_command' not in names[i]
            assert 'write_file' in names[3]
            server.shutdown()
    return {'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'requests': len(requests),
            'toolsByTask': names, 'schemaUtf8Bytes': [len(json.dumps(s, ensure_ascii=False, separators=(',', ':')).encode()) for s in schemas],
            'note': '结构字节数不是厂商计费 token；分类额外请求为零'}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--launcher')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    report = verify(args.launcher)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))
