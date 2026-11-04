"""用真实 CLI 和 localhost 截断 HTTP 正文验证两种协议的自动降级、实际退避及部分输出边界。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
KEY = 'transport-offline-fixture-key'
MODEL = 'transport-offline-fixture'
TASK = '检查当前项目，完成后简短回答。'
ANSWER = '正文断流后已恢复，完整回复正常。'
PARTIAL = '这是已经显示的部分回复。'
FORMATS = ('chat', 'responses')
SCENARIOS = ('recover', 'third-retry', 'exhausted', 'partial-text', 'http-recover', 'http-nonstream')
ENVIRONMENT_HOOKS = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
                     'JVM_CONFIG_MAVEN_PROPS', 'JAVACMD', 'JAVA_HOME', 'M2_HOME'}


def write(destination, value):
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(value, encoding='utf-8')


def java_executable():
    configured = os.environ.get('JAVA_HOME')
    candidate = Path(configured) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if configured else None
    executable = candidate if candidate and candidate.is_file() else shutil.which('java')
    if not executable:
        raise RuntimeError('本地断流验收需要已安装的 Java 21')
    return Path(executable).resolve()


def invoke(java, launcher, workspace, user_home, arguments, input_text=''):
    """独立进程、独立 home、显式本地配置；不读取真实用户的私有配置。"""
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() not in ENVIRONMENT_HOOKS
                   and not key.upper().startswith(('MAVEN_', 'HARNESS_', 'YLC_'))}
    environment.update({'JAVA_HOME': str(java.parent.parent), 'HOME': str(user_home),
                        'USERPROFILE': str(user_home), 'XDG_CONFIG_HOME': str(user_home / '.config')})
    if launcher:
        launch = [str(launcher)]
        environment['JDK_JAVA_OPTIONS'] = '"-Duser.home=' + str(user_home) + '"'
    else:
        launch = [str(java), '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                  '-Dstderr.encoding=UTF-8', '-Duser.home=' + str(user_home), '-jar', str(JAR)]
    if os.name == 'nt':
        write(workspace / 'cli-arguments.txt', '\n'.join(json.dumps(arg, ensure_ascii=False) for arg in arguments) + '\n')
        arguments = ['@cli-arguments.txt']
    started = time.monotonic()
    result = subprocess.run(launch + arguments, cwd=workspace, env=environment, input=input_text,
                            capture_output=True, text=True, encoding='utf-8', timeout=65)
    duration = time.monotonic() - started
    assert KEY not in result.stdout + result.stderr, '验收凭据进入 CLI 输出'
    return result, duration


def message(identifier, content):
    return {'type': 'message', 'id': identifier, 'status': 'completed', 'role': 'assistant',
            'content': [{'type': 'output_text', 'text': content}]}


def answer(api_format):
    if api_format == 'chat':
        return {'choices': [{'message': {'role': 'assistant', 'content': ANSWER}, 'finish_reason': 'stop'}],
                'usage': {'prompt_tokens': 11, 'completion_tokens': 3,
                          'prompt_tokens_details': {'cached_tokens': 4}}}
    return {'id': 'transport-response-final', 'status': 'completed',
            'output': [message('transport-answer', ANSWER)],
            'usage': {'input_tokens': 11, 'output_tokens': 3,
                      'input_tokens_details': {'cached_tokens': 4}}}


def partial_delta(api_format):
    if api_format == 'chat':
        event = {'choices': [{'index': 0, 'delta': {'content': PARTIAL}, 'finish_reason': None}]}
    else:
        event = {'type': 'response.output_text.delta', 'sequence_number': 0, 'item_id': 'partial-message',
                 'output_index': 0, 'content_index': 0, 'delta': PARTIAL}
    return 'data: ' + json.dumps(event, ensure_ascii=False) + '\n\n'


class Fixture:
    def __init__(self, api_format, scenario):
        self.api_format, self.scenario = api_format, scenario
        self.requests, self.paths, self.times, self.errors = [], [], [], []
        self.lock = threading.Lock()
        self.expected_requests = {'recover': 2, 'third-retry': 4, 'exhausted': 4, 'partial-text': 1,
                                  'http-recover': 3, 'http-nonstream': 3}[scenario]

    @property
    def endpoint(self):
        return '/v1/chat/completions' if self.api_format == 'chat' else '/v1/responses'

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send_body(self, media_type, body, truncated=False, status=200):
                encoded = body.encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', media_type)
                self.send_header('Content-Length', str(len(encoded) + (8192 if truncated else 0)))
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(encoded)
                self.wfile.flush()
                self.close_connection = True
                if truncated:
                    # 已收到合法响应头后正文才提前关闭，避免 HttpClient 在无响应头时透明重发。
                    self.connection.shutdown(socket.SHUT_WR)

            def do_POST(self):
                try:
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    with fixture.lock:
                        index = len(fixture.requests)
                        fixture.requests.append(body)
                        fixture.paths.append(self.path)
                        fixture.times.append(time.monotonic())
                    assert self.path == fixture.endpoint, '降级请求改变了配置的 API 协议'
                    assert self.headers.get('Authorization') == 'Bearer ' + KEY, '没有使用模拟凭据'
                    assert body.get('model') == MODEL, '降级请求改变了模型'
                    main_field, forbidden_field = ('messages', 'input') if fixture.api_format == 'chat' else ('input', 'messages')
                    assert isinstance(body.get(main_field), list) and forbidden_field not in body, '请求格式不匹配'
                    expected_stream = (fixture.scenario == 'http-recover'
                                       or fixture.scenario != 'http-nonstream' and index == 0)
                    assert bool(body.get('stream')) == expected_stream, '请求未保持预期的流模式'
                    assert index < fixture.expected_requests, '自动重试超过次数上限'
                    if fixture.scenario in ('http-recover', 'http-nonstream'):
                        if index < 2:
                            self.send_body('application/json', '{"error":{"message":"temporary unavailable"}}',
                                           status=429 if index == 0 else 503)
                        else:
                            self.send_body('application/json', json.dumps(answer(fixture.api_format), ensure_ascii=False))
                    elif fixture.scenario == 'partial-text':
                        self.send_body('text/event-stream', partial_delta(fixture.api_format), truncated=True)
                    elif index == 0:
                        # 保活注释不能算作已经显示的文本，也不能阻止兼容回退。
                        self.send_body('text/event-stream', ': localhost keepalive\n\n', truncated=True)
                    elif fixture.scenario == 'recover' or fixture.scenario == 'third-retry' and index == 3:
                        self.send_body('application/json', json.dumps(answer(fixture.api_format), ensure_ascii=False))
                    else:
                        self.send_body('application/json', ' ', truncated=True)
                except (BrokenPipeError, ConnectionResetError):
                    # 客户端在取消或读失败时关闭连接是正常现象。
                    pass
                except Exception as failure:
                    fixture.errors.append(repr(failure).replace(KEY, '[验收凭据]'))
                    try:
                        self.send_body('application/json', '{"error":{"message":"local fixture failed"}}', status=500)
                    except OSError:
                        pass

        return Provider


def verify_requests(fixture):
    assert not fixture.errors, fixture.errors
    assert len(fixture.requests) == fixture.expected_requests, (
        fixture.scenario, '实际请求次数', len(fixture.requests), '预期请求次数', fixture.expected_requests)
    assert all(path == fixture.endpoint for path in fixture.paths), fixture.paths
    initial = {key: value for key, value in fixture.requests[0].items() if key != 'stream'}
    for retry in fixture.requests[1:]:
        assert bool(retry.get('stream')) == (fixture.scenario == 'http-recover'), '重试的流模式不符合预期'
        assert {key: value for key, value in retry.items() if key != 'stream'} == initial, '重试修改了消息、工具或模型参数'
    intervals = [second - first for first, second in zip(fixture.times, fixture.times[1:])]
    minimums = [1.0, 2.0, 4.0][:len(intervals)]
    for index, (actual, expected) in enumerate(zip(intervals, minimums), 1):
        assert actual >= expected - .05, ('重试没有遵守实际递增等待', index, actual, expected)
    return intervals


def verify_output(result, fixture):
    if fixture.scenario == 'partial-text':
        assert result.returncode == 1, (result.returncode, result.stdout, result.stderr)
        assert '已输出部分回复' in result.stderr, result.stderr
        assert result.stdout.count(PARTIAL) == 1, result.stdout
        assert '正在自动改用' not in result.stderr, result.stderr
        return None
    expected_code = 1 if fixture.scenario == 'exhausted' else 0
    assert result.returncode == expected_code, (result.returncode, result.stdout, result.stderr)
    report = json.loads(result.stdout)
    # 启动器可由 JVM 输出 home 选项提示；JSON 的 stdout 仍必须是单个对象且不能含进度。
    assert '正在自动改用' not in result.stderr, result.stderr
    assert '正在自动改用' not in result.stdout, result.stdout
    usage = report['usageReport']
    assert usage['requests'] == fixture.expected_requests, usage
    if fixture.scenario == 'exhausted':
        assert '重试 3 次，仍未成功' in report['error'], report
        assert '未收到完整响应' in report['error'], report
        assert 'IOException' in report['error'] or 'EOFException' in report['error'], report
        assert usage['inputReportedRequests'] == usage['outputReportedRequests'] == 0, usage
    else:
        assert report['status'] == 'COMPLETED' and report['answer'] == ANSWER, report
        assert report['iterations'] == 1, report
        assert report['provider'] == ('openai-compatible' if fixture.api_format == 'chat' else 'openai-responses'), report
        assert usage['inputReportedRequests'] == usage['outputReportedRequests'] == usage['cacheReportedRequests'] == 1, usage
        assert usage['total'] == {'inputTokens': 11, 'outputTokens': 3, 'cachedInputTokens': 4}, usage
    return report


def verify_checkpoint(workspace, fixture):
    checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
    assert len(checkpoints) == 1, '重试必须保留同一会话'
    saved = json.loads(checkpoints[0].read_text(encoding='utf-8'))
    expected_status = 'FAILED' if fixture.scenario in ('exhausted', 'partial-text') else 'COMPLETED'
    assert saved['status'] == expected_status, saved['status']
    users = [item['content'] for item in saved['transcript'] if item['role'] == 'USER']
    assert users == [TASK], '重试重复追加了用户任务'
    assert not any(item['role'] == 'TOOL' for item in saved['transcript']), '本验收没有授权任何工具调用'
    for stored in (workspace / '.harness').rglob('*'):
        if stored.is_file():
            content = stored.read_text(encoding='utf-8', errors='replace')
            assert KEY not in content, '验收凭据进入持久化数据'
    return saved


def verify(launcher=None, api_format=None, scenario=None):
    java = java_executable()
    if launcher:
        if not launcher.is_absolute() or not launcher.is_file():
            raise ValueError('--launcher 必须是已安装 ylc 启动器的绝对文件路径')
        launcher = launcher.resolve()
    elif not JAR.is_file():
        raise RuntimeError('找不到源码打包 JAR；请先构建或通过 --launcher 指定启动器')
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    records = []
    formats = [api_format] if api_format else FORMATS
    scenarios = [scenario] if scenario else SCENARIOS
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='transport-fallback-', dir=target) as temporary:
        base = Path(temporary).resolve()
        for selected_format in formats:
            for selected_scenario in scenarios:
                name = selected_format + '-' + selected_scenario
                workspace, user_home = base / name / 'workspace', base / name / 'home'
                workspace.mkdir(parents=True)
                user_home.mkdir(parents=True)
                fixture = Fixture(selected_format, selected_scenario)
                with ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler()) as server:
                    serving = threading.Thread(target=server.serve_forever, daemon=True)
                    serving.start()
                    config = workspace / 'offline.yaml'
                    write(config, f'provider: openai-compatible\napi-format: {selected_format}\nmodel: {MODEL}\n'
                          + f'api-key: {KEY}\napi-key-env: ""\nbase-url: http://127.0.0.1:{server.server_port}/v1\n'
                          + 'timeout-seconds: 10\nweb:\n  enabled: false\npermissions:\n  auto-review: false\n')
                    original_config = config.read_bytes()
                    arguments = ['--config', str(config), '--workspace', str(workspace), '--no-color']
                    if selected_scenario == 'http-nonstream':
                        arguments += ['--no-stream']
                    input_text = ''
                    if selected_scenario == 'partial-text':
                        input_text = TASK + '\n/exit\n'
                    else:
                        arguments += ['--quiet', '--json', 'run', TASK]
                    try:
                        result, duration = invoke(java, launcher, workspace, user_home, arguments, input_text)
                        # 失败时同时提供 CLI 诊断与本地请求统计，便于在旧安装包重现根因。
                        assert len(fixture.requests) == fixture.expected_requests, (
                            name, len(fixture.requests), fixture.expected_requests, result.stdout, result.stderr)
                        intervals = verify_requests(fixture)
                        report = verify_output(result, fixture)
                        saved = verify_checkpoint(workspace, fixture)
                        assert config.read_bytes() == original_config, '验收运行改写了配置'
                    finally:
                        server.shutdown()
                        serving.join(5)
                records.append({'case': name, 'status': 'PASS', 'apiFormat': selected_format,
                                'endpoint': fixture.endpoint, 'requests': len(fixture.requests),
                                'nonStreamRetries': sum(not request.get('stream') for request in fixture.requests[1:]),
                                'streamRetries': sum(bool(request.get('stream')) for request in fixture.requests[1:]),
                                'actualRetryIntervalsSeconds': [round(interval, 3) for interval in intervals],
                                'durationSeconds': round(duration, 3), 'exitCode': result.returncode,
                                'savedProvider': saved['provider'], 'savedStatus': saved['status'],
                                'sameRequest': True, 'sameSession': True,
                                'usageReport': report['usageReport'] if report else None,
                                'error': report.get('error') if report else '已输出部分回复；本次不自动重试'})
    return {'status': 'PASS', 'cases': records, 'localhostRequests': sum(record['requests'] for record in records),
            'durationSeconds': round(time.monotonic() - started, 3),
            'scope': '仅 localhost；独立工作区、用户 home、私有模拟配置和 CLI 进程。'
                     '实际截断 HTTP 正文并验证 1/2/4 秒退避；未调用真实厂商，未执行任何模型工具。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='已安装 ylc 启动器的绝对路径；省略时验证源码 JAR')
    parser.add_argument('--result', type=Path, help='保存不含凭据的 JSON 验收证据')
    parser.add_argument('--format', dest='api_format', choices=FORMATS, help='仅验证指定协议')
    parser.add_argument('--scenario', choices=SCENARIOS, help='仅验证指定场景，便于重现旧版本失败')
    options = parser.parse_args()
    result = verify(options.launcher, options.api_format, options.scenario)
    if options.result:
        write(options.result, json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
