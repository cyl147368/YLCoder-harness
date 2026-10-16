"""使用真实 jar 验证分词器选择及联网前失败检查；所有请求均为 localhost。"""
import json
import os
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
REQUESTS = []


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        REQUESTS.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
        body = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': 'Done'},
                                      'finish_reason': 'stop'}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    assert JAR.is_file(), '请先构建 shaded jar'
    with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(prefix='harness-token-budget-') as temporary:
                workspace = pathlib.Path(temporary)
                config = workspace / 'offline.yaml'
                config.write_text('{}\n', encoding='utf-8')
                launch = ['java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                          '-Dstderr.encoding=UTF-8', '-jar', str(JAR)]
                common = ['--workspace', temporary, '--config', str(config),
                          '--provider', 'openai-compatible', '--model', 'fixture',
                          '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                          '--api-key-env', 'HARNESS_SMOKE_UNUSED_KEY', '--quiet', '--json',
                          '--context-budget', '6500']
                cjk = '修复边界条件并保留中文注释。' * 1000

                def invoke(arguments, expected):
                    cli_arguments = common + arguments
                    if os.name == 'nt':
                        # 保留原始中文给分词器，避免 Java 原生 ACP 转换让测试统计问号。
                        argument_file = workspace / 'cli-arguments.txt'
                        argument_file.write_text('\n'.join(json.dumps(value, ensure_ascii=False)
                                                          for value in cli_arguments) + '\n', encoding='utf-8')
                        cli_arguments = ['@' + argument_file.name]
                    environment = os.environ.copy()
                    environment.pop('HARNESS_SMOKE_UNUSED_KEY', None)
                    result = subprocess.run(launch + cli_arguments, capture_output=True, text=True,
                                            encoding='utf-8', cwd=workspace, env=environment, timeout=30)
                    assert result.returncode == expected, (result.stdout, result.stderr)
                    return json.loads(result.stdout)

                baseline = invoke(['--token-counter', 'utf16', 'run', cjk], 0)
                assert baseline['status'] == 'COMPLETED' and len(REQUESTS) == 1
                assert [message['content'] for message in REQUESTS[0]['messages']
                        if message['role'] == 'user'] == [cjk], '预算对照必须收到完整中文输入'
                for counter in ('cl100k', 'o200k'):
                    before = len(REQUESTS)
                    failed = invoke(['--token-counter', counter, 'resume', baseline['session'], 'Verify'], 1)
                    assert failed['status'] == 'FAILED' and any(marker in failed['error'] for marker in ('context budget', '上下文预算')), failed
                    assert len(REQUESTS) == before, '超过预算的必需中文输入到达厂商'
                    small = invoke(['--token-counter', counter, 'run', '检查边界行为'], 0)
                    assert small['status'] == 'COMPLETED' and len(REQUESTS) == before + 1
                    assert [message['content'] for message in REQUESTS[-1]['messages']
                            if message['role'] == 'user'] == ['检查边界行为']
                print(json.dumps({'status': 'PASS', 'modelRequests': len(REQUESTS),
                                  'checks': 'packaged CLI selects both encodings; CJK resume fails before network; UTF16 compatibility'}, indent=2))
        finally:
            server.shutdown()


if __name__ == '__main__':
    main()
