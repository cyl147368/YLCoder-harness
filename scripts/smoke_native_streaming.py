"""仅使用 localhost 夹具验证打包的原生 Chat Completions 流。"""
import json
import os
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'


class Scenario:
    def __init__(self, mode, workspace):
        self.mode = mode
        self.workspace = pathlib.Path(workspace)
        self.requests = []
        self.errors = []
        self.release = threading.Event()
        self.call_sent = threading.Event()

    def handler(self):
        scenario = self

        class Provider(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    assert self.path == '/v1/chat/completions', self.path
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    scenario.requests.append(request)
                    assert request.get('stream') is True, request
                    assert 'stream_options' not in request, '通用网关不能强制要求 stream_options'
                    assert 'Authorization' not in self.headers, '夹具不应收到已配置的 API 密钥'
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()

                    def event(payload):
                        text = json.dumps(payload) if isinstance(payload, dict) else payload
                        self.wfile.write(('data: ' + text + '\n\n').encode('utf-8'))
                        self.wfile.flush()

                    def chunk(delta, finish=None):
                        choice = {'index': 0, 'delta': delta}
                        if finish is not None:
                            choice['finish_reason'] = finish
                        event({'choices': [choice]})

                    number = len(scenario.requests)
                    if scenario.mode == 'early':
                        chunk({'role': 'assistant', 'content': '原生'})
                        if not scenario.release.wait(10):
                            raise AssertionError('父进程未释放最终文本')
                        chunk({'content': '流已验证。'})
                        chunk({}, 'stop')
                    elif scenario.mode == 'json':
                        chunk({'content': '原生 JSON 已验证。'})
                        chunk({}, 'stop')
                    elif scenario.mode in ('tool', 'unfinished') and number == 1:
                        filename = 'mutation.txt' if scenario.mode == 'tool' else 'must-not-exist.txt'
                        arguments = json.dumps({'path': filename, 'content': 'one mutation\n'})
                        split = len(arguments) // 2
                        chunk({'tool_calls': [{'index': 0, 'id': 'write-once', 'type': 'function',
                                              'function': {'name': 'write_file', 'arguments': arguments[:split]}}]})
                        chunk({'tool_calls': [{'index': 0, 'function': {'arguments': arguments[split:]}}]})
                        # 参数已经合法，但厂商响应尚未完整结束。
                        scenario.call_sent.set()
                        if not scenario.release.wait(10):
                            raise AssertionError('父进程未释放工具完成事件')
                        chunk({}, 'tool_calls')
                        if scenario.mode == 'unfinished':
                            return  # 即使已有 finish_reason，也刻意在没有 [DONE] 时结束输入。
                    else:
                        if scenario.mode == 'tool':
                            results = [m for m in request['messages'] if m['role'] == 'tool']
                            assert len(results) == 1 and results[0]['tool_call_id'] == 'write-once', results
                            assert 'mutation.txt' in results[0]['content'], results
                            assert scenario.workspace.joinpath('mutation.txt').read_text(encoding='utf-8') == 'one mutation\n'
                            chunk({'content': '修改完成一次。'})
                        else:
                            assert scenario.mode == 'unfinished' and number == 2, (scenario.mode, number)
                            assert not any(m.get('tool_calls') for m in request['messages']), request
                            chunk({'content': '已恢复，未执行修改。'})
                        chunk({}, 'stop')
                    event({'choices': [], 'usage': {'prompt_tokens': 12, 'completion_tokens': 3,
                                                   'total_tokens': 15}})
                    event('[DONE]')
                except Exception as failure:
                    scenario.errors.append(repr(failure))
                    self.close_connection = True

        return Provider


def command(workspace, port):
    config = pathlib.Path(workspace) / 'fixture.yaml'
    config.write_text('{}\n', encoding='utf-8')
    return ['java', '-jar', str(JAR), '--workspace', str(workspace), '--config', str(config),
            '--provider', 'openai-compatible', '--model', 'native-stream-fixture',
            '--base-url', f'http://127.0.0.1:{port}/v1',
            '--api-key-env', 'HARNESS_NATIVE_STREAM_FIXTURE_KEY', '--quiet']


def start(args, environment):
    return subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding='utf-8', env=environment)


def stop(process):
    if process.poll() is None:
        process.kill()
        process.communicate(timeout=5)


def result(process, prefix=''):
    stdout, stderr = process.communicate(timeout=20)
    return process.returncode, prefix + stdout, stderr


def check_early(common, scenario, environment):
    process = start(common + ['run', '回复原生测试短语。'], environment)
    first = []
    errors = []
    arrived = threading.Event()

    def read_first():
        # 流始终只使用一个读取器：读取一个字符后混用 TextIO 缓冲与 communicate，
        # 可能丢失 TextIO 已经缓冲的字节。
        while True:
            value = process.stdout.read(1)
            if not value:
                return
            first.append(value)
            arrived.set()

    reader = threading.Thread(target=read_first, daemon=True)
    # 提前排空 stderr，防止状态输出填满管道而阻塞仍在读取 stdout 的测试。
    error_reader = threading.Thread(target=lambda: errors.append(process.stderr.read()), daemon=True)
    reader.start()
    error_reader.start()
    try:
        assert arrived.wait(8), '服务保持完成事件期间没有 stdout'
        assert first and first[0] == '原', first
        assert process.poll() is None, '在保持的完成事件释放前 CLI 已完成'
        scenario.release.set()
        process.wait(timeout=20)
        reader.join(2)
        error_reader.join(2)
        assert not reader.is_alive() and not error_reader.is_alive(), '进程退出后输出读取器未结束'
        stdout, stderr = ''.join(first), ''.join(errors)
        assert process.returncode == 0, (stdout, stderr)
        assert stdout.count('原生流已验证。') == 1, stdout
        assert len(scenario.requests) == 1, scenario.requests
    finally:
        scenario.release.set()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        reader.join(2)
        error_reader.join(2)


def check_json(common, scenario, environment):
    process = start(common + ['--json', 'run', '返回 JSON 测试短语。'], environment)
    try:
        code, stdout, stderr = result(process)
        decoded = json.loads(stdout)  # 任何流式文本泄漏都会让此检查失败。
        assert code == 0 and decoded['status'] == 'COMPLETED', (decoded, stderr)
        assert decoded['answer'] == '原生 JSON 已验证。', decoded
        assert len(scenario.requests) == 1, scenario.requests
    finally:
        stop(process)


def check_tools(common, scenario, environment):
    process = start(common + ['--json', 'run', '恰好应用一次夹具修改。'], environment)
    filename = 'mutation.txt' if scenario.mode == 'tool' else 'must-not-exist.txt'
    try:
        assert scenario.call_sent.wait(8), 'CLI 未收到分片工具调用'
        assert not scenario.workspace.joinpath(filename).exists(), '流完成之前执行了工具'
        scenario.release.set()
        code, stdout, stderr = result(process)
        decoded = json.loads(stdout)
        checkpoint = scenario.workspace / '.harness/sessions' / (decoded['session'] + '.json')
        saved = json.loads(checkpoint.read_text(encoding='utf-8'))
        if scenario.mode == 'tool':
            assert code == 0 and decoded['status'] == 'COMPLETED', (decoded, stderr)
            assert decoded['answer'] == '修改完成一次。', decoded
            assert scenario.workspace.joinpath(filename).read_text(encoding='utf-8') == 'one mutation\n'
            calls = [call for message in saved['transcript'] for call in message.get('toolCalls', [])]
            results = [message for message in saved['transcript'] if message['role'] == 'TOOL']
            assert len(calls) == 1 and calls[0]['id'] == 'write-once', calls
            assert len(results) == 1 and results[0]['toolCallId'] == 'write-once', results
            assert len(scenario.requests) == 2, scenario.requests
        else:
            assert code == 1 and decoded['status'] == 'FAILED', (decoded, stderr)
            assert not scenario.workspace.joinpath(filename).exists(), '不完整流执行了修改'
            assert not any(m.get('toolCalls') for m in saved['transcript']), saved
            assert saved['status'] == 'FAILED' and saved['transcript'], saved
            assert len(scenario.requests) == 1, '不完整响应被自动重试'
            resumed = start(common + ['--json', 'resume', decoded['session'], '检查当前状态，不重复修改。'], environment)
            try:
                resumed_code, resumed_stdout, resumed_stderr = result(resumed)
                recovered = json.loads(resumed_stdout)
                assert resumed_code == 0 and recovered['status'] == 'COMPLETED', (recovered, resumed_stderr)
                assert recovered['answer'] == '已恢复，未执行修改。', recovered
                assert not scenario.workspace.joinpath(filename).exists()
                assert len(scenario.requests) == 2
            finally:
                stop(resumed)
    finally:
        scenario.release.set()
        stop(process)


def main():
    assert JAR.is_file(), '打包冒烟检查前请运行 mvn verify'
    environment = os.environ.copy()
    environment.pop('HARNESS_NATIVE_STREAM_FIXTURE_KEY', None)
    request_count = 0
    for mode in ('early', 'json', 'tool', 'unfinished'):
        with tempfile.TemporaryDirectory(prefix='harness-native-stream-') as workspace:
            scenario = Scenario(mode, workspace)
            with ThreadingHTTPServer(('127.0.0.1', 0), scenario.handler()) as server:
                threading.Thread(target=server.serve_forever, daemon=True).start()
                try:
                    common = command(workspace, server.server_port)
                    if mode == 'early':
                        check_early(common, scenario, environment)
                    elif mode == 'json':
                        check_json(common, scenario, environment)
                    else:
                        check_tools(common, scenario, environment)
                    assert not scenario.errors, scenario.errors
                    request_count += len(scenario.requests)
                finally:
                    scenario.release.set()
                    server.shutdown()
    print(json.dumps({'status': 'PASS', 'modelRequests': request_count,
                      'checks': ['在保持完成事件期间收到原生 stdout', '最终文本不重复', 'parseable JSON',
                                 '一次完整修改及结果回放', '未完成流不修改文件',
                                 '失败检查点恢复时不重复执行']}))


if __name__ == '__main__':
    main()
