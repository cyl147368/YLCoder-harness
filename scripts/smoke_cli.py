"""使用确定性的本地厂商夹具运行打包 CLI，不使用凭据。"""
import json
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
requests = []


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(body)
        step = len(requests)
        calls = [
            ('search_files', {'query': 'return 41'}),
            ('read_file', {'path': 'Answer.java'}),
            ('edit_file', {'path': 'Answer.java', 'old_text': 'return 41;', 'new_text': 'return 42;'}),
            ('run_command', {'command': 'javac Answer.java && java Answer'}),
            ('changes', {}),
        ]
        if step <= len(calls):
            tool, arguments = calls[step - 1]
            message = {'role': 'assistant', 'content': '', 'tool_calls': [
                {'id': f'c{step}', 'type': 'function', 'function': {
                    'name': tool, 'arguments': json.dumps(arguments)}}]}
            finish = 'tool_calls'
        else:
            message = {'role': 'assistant', 'content': '已验证 Answer 返回 42。'}
            finish = 'stop'
        response = json.dumps({'choices': [{'message': message, 'finish_reason': finish}],
                               'usage': {'prompt_tokens': 100, 'completion_tokens': 20}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


def main():
    assert JAR.is_file(), '打包冒烟测试前请运行 mvn verify'
    with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(prefix='harness-smoke-') as temporary:
                workspace = pathlib.Path(temporary)
                config = workspace / 'offline.yaml'
                config.write_text('{}\n', encoding='utf-8')
                source = workspace / 'Answer.java'
                source.write_text('public class Answer { static int value() { return 41; } '
                                  'public static void main(String[] args) { System.out.println(value()); } }\n', encoding='utf-8')
                common = ['java', '-jar', str(JAR), '--workspace', str(workspace), '--config', str(config),
                          '--provider', 'openai-compatible', '--model', 'fake',
                          '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                          '--api-key-env', 'HARNESS_SMOKE_UNUSED_KEY', '--quiet', '--json', '--allow-commands']
                run = subprocess.run(common + ['run', '修复 Answer.value，使其返回 42，然后编译并运行。'],
                                     capture_output=True, text=True, encoding='utf-8', timeout=45)
                assert run.returncode == 0, (run.stdout, run.stderr)
                result = json.loads(run.stdout)
                assert result['status'] == 'COMPLETED', result
                assert 'return 42;' in source.read_text(encoding='utf-8')
                tool_results = [m['content'] for m in requests[-1]['messages'] if m['role'] == 'tool']
                assert len(tool_results) == 5, tool_results
                assert any(marker in tool_results[-2] for marker in ('Exit code: 0', '退出码：0')) and '42' in tool_results[-2], tool_results[-2]
                assert '--- ' in tool_results[-1] and '+++ ' in tool_results[-1] and '-public class Answer' in tool_results[-1], tool_results[-1]
                resumed = subprocess.run(common + ['resume', result['session'], '确认验证结果。'],
                                         capture_output=True, text=True, encoding='utf-8', timeout=20)
                assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
                assert json.loads(resumed.stdout)['session'] == result['session']
                print(json.dumps({'status': 'PASS', 'modelRequests': len(requests),
                                  'workflow': 'search/read/edit_file/javac/java/changes/resume',
                                  'jar': str(JAR)}, indent=2))
        finally:
            server.shutdown()


if __name__ == '__main__':
    main()
