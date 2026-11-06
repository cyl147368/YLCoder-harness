"""通过真实 JAR 或全局 ylc 启动器验证本地执行记录；模型仅使用 localhost 模拟服务。"""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time
import zipfile
from read_model_trace import read_wire_events

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
KEY = 'trace-offline-main-fixture-key'
REVIEW_KEY = 'trace-offline-review-fixture-key'
HEADER_VALUE = 'trace-offline-header-fixture-value'
TASK = '记录本次实际提示词并验证工具结果。'
FOLLOWUP = '恢复后根据刚才的结果继续。'
ANSWER = '本地执行记录验收已完成。'
PREFIX = '工具输出前段标记'
TAIL = '工具输出隐藏尾部标记'
PARTIAL = '已显示但未完成的回复标记'
HOOKS = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
         'JVM_CONFIG_MAVEN_PROPS', 'JAVACMD', 'JAVA_HOME', 'M2_HOME'}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def java_executable():
    configured = os.environ.get('JAVA_HOME')
    candidate = Path(configured) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if configured else None
    executable = candidate if candidate and candidate.is_file() else shutil.which('java')
    if not executable:
        raise RuntimeError('执行记录验收需要 Java 21')
    return Path(executable).resolve()


def invoke(java, jar, launcher, workspace, home, arguments, input_text=''):
    # 使用显式模拟配置和独立 home，不加载真实用户凭据或全局 JVM 注入参数。
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() not in HOOKS and not key.upper().startswith(('MAVEN_', 'HARNESS_', 'YLC_'))}
    environment.update({'JAVA_HOME': str(java.parent.parent), 'HOME': str(home), 'USERPROFILE': str(home),
                        'XDG_CONFIG_HOME': str(home / '.config'), 'YLC_TRACE_FIXTURE_HEADER': HEADER_VALUE})
    if launcher:
        launch = [str(launcher)]
        environment['JDK_JAVA_OPTIONS'] = '"-Duser.home=' + str(home) + '"'
    else:
        launch = [str(java), '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                  '-Dstderr.encoding=UTF-8', '-Duser.home=' + str(home), '-jar', str(jar)]
    if os.name == 'nt':
        argument_file = workspace / 'cli-arguments.txt'
        argument_file.write_text('\n'.join(json.dumps(arg, ensure_ascii=False) for arg in arguments) + '\n', encoding='utf-8')
        arguments = ['@cli-arguments.txt']
    started = time.monotonic()
    result = subprocess.run(launch + arguments, cwd=workspace, env=environment, input=input_text,
                            capture_output=True, text=True, encoding='utf-8', timeout=60)
    elapsed = round((time.monotonic() - started) * 1000, 3)
    # 全局启动器通过此 JVM 参数隔离 home；JDK 自带的提示不是应用失败。
    if launcher:
        notice = 'NOTE: Picked up JDK_JAVA_OPTIONS: "-Duser.home=' + str(home) + '"\n'
        result.stderr = result.stderr.replace(notice, '')
    for secret in (KEY, REVIEW_KEY, HEADER_VALUE):
        assert secret not in result.stdout + result.stderr, '模拟连接凭据进入 CLI 输出'
    return result, elapsed


def result_payload(api_format, text, tool=None, sequence=1):
    usage = {'input_tokens': 17, 'output_tokens': 5, 'input_tokens_details': {'cached_tokens': 9}}
    if api_format == 'responses':
        output = [{'type': 'message', 'id': 'trace-message-' + str(sequence), 'status': 'completed', 'role': 'assistant',
                   'content': [{'type': 'output_text', 'text': text}]}]
        if tool:
            identifier, name, arguments = tool
            output.append({'type': 'function_call', 'id': 'item-' + identifier, 'call_id': identifier,
                           'status': 'completed', 'name': name, 'arguments': arguments})
        return {'id': 'trace-response-' + str(sequence), 'status': 'completed', 'output': output, 'usage': usage}
    message = {'role': 'assistant', 'content': text}
    if tool:
        identifier, name, arguments = tool
        message['tool_calls'] = [{'id': identifier, 'type': 'function', 'function': {'name': name, 'arguments': arguments}}]
    return {'choices': [{'message': message, 'finish_reason': 'tool_calls' if tool else 'stop'}],
            'usage': {'prompt_tokens': 17, 'completion_tokens': 5, 'prompt_tokens_details': {'cached_tokens': 9}}}


class Fixture:
    def __init__(self, api_format, scenario):
        self.api_format, self.scenario = api_format, scenario
        self.requests, self.paths, self.times, self.failures = [], [], [], []
        self.responses = []
        self.main_calls = 0
        self.lock = threading.Lock()

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send(self, status, media_type, text):
                fixture.responses.append({'status': status, 'body': text})
                body = text.encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', media_type)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    is_review = request.get('model') == 'trace-review-model'
                    protocol = 'responses' if is_review else fixture.api_format
                    expected_path = '/v1/responses' if protocol == 'responses' else '/v1/chat/completions'
                    assert self.path == expected_path, '协议或端点发生错误'
                    assert self.headers.get('Authorization') == 'Bearer ' + (REVIEW_KEY if is_review else KEY)
                    assert self.headers.get('X-Fixture') == HEADER_VALUE
                    field, forbidden = ('input', 'messages') if protocol == 'responses' else ('messages', 'input')
                    assert isinstance(request.get(field), list) and forbidden not in request
                    with fixture.lock:
                        fixture.requests.append(request)
                        fixture.paths.append(self.path)
                        fixture.times.append(time.monotonic())
                        if not is_review:
                            fixture.main_calls += 1
                        main_index = fixture.main_calls
                        request_index = len(fixture.requests)
                    if is_review:
                        assert fixture.scenario == 'review'
                        assert not request.get('tools'), '审核分类器不能获得执行工具'
                        text = json.dumps({'decision': 'approve', 'reason': '审核过程记录标记'}, ensure_ascii=False)
                        payload = result_payload(protocol, text, sequence=request_index)
                    elif fixture.scenario == 'retry' and main_index < 4:
                        assert bool(request.get('stream')) == (main_index == 1)
                        self.send(400, 'application/json', json.dumps({'error': {'message': 'stream unsupported fixture ' + KEY}}))
                        return
                    elif fixture.scenario == 'partial':
                        assert request.get('stream')
                        events = 'data: ' + json.dumps({'choices': [{'index': 0, 'delta': {'content': PARTIAL}, 'finish_reason': None}]}, ensure_ascii=False) + '\n\n'
                        events += 'data: ' + json.dumps({'error': {'message': 'fixture stream interrupted'}}) + '\n\n'
                        self.send(200, 'text/event-stream', events)
                        return
                    elif fixture.scenario == 'tools' and main_index == 1:
                        payload = result_payload(protocol, '准备读取文件。', ('trace-read', 'read_file', '{"path":"sample.txt"}'), sequence=request_index)
                    elif fixture.scenario == 'review' and main_index == 1:
                        payload = result_payload(protocol, '准备审核日期查询。', ('trace-date', 'run_command', '{"command":"date"}'), sequence=request_index)
                    else:
                        payload = result_payload(protocol, ANSWER, sequence=request_index)
                    self.send(200, 'application/json', json.dumps(payload, ensure_ascii=False))
                except Exception as failure:
                    fixture.failures.append(type(failure).__name__ + ': ' + str(failure).replace(KEY, '[模拟凭据]').replace(REVIEW_KEY, '[模拟凭据]'))
                    try:
                        self.send(500, 'application/json', '{"error":{"message":"localhost fixture failed"}}')
                    except OSError:
                        pass

        return Provider


def config_text(port, api_format, scenario):
    mode = 'review' if scenario == 'review' else 'manual'
    trace = 'trace:\n  enabled: false\n' if scenario == 'disabled' else 'trace:\n  tool-result-max-chars: 128\n'
    review = ('  reviewer:\n    model: trace-review-model\n    api-format: responses\n    api-key: ' + REVIEW_KEY + '\n') if scenario == 'review' else ''
    return ('provider: openai-compatible\napi-format: ' + api_format + '\nmodel: trace-main-model\napi-key: ' + KEY
            + '\nbase-url: http://127.0.0.1:' + str(port) + '/v1\nheader-env:\n  X-Fixture: YLC_TRACE_FIXTURE_HEADER\n'
            + 'web:\n  enabled: false\npermissions:\n  mode: ' + mode + '\n' + review + trace)


def trace_contents(workspace, report):
    file = Path(report['traceFile'])
    assert file.is_file() and file.parent.parent == workspace / '.harness/logs'
    text = file.read_text(encoding='utf-8')
    assert '# YLCoder 执行日志' in text and '## 任务结束' in text
    for secret in (KEY, REVIEW_KEY, HEADER_VALUE):
        assert secret not in text, '模拟连接凭据进入日志'
    for anchor in re.findall(r'\]\(#((?:content|wire)-\d+)\)', text):
        assert '<a id="' + anchor + '"></a>' in text, '日志内容引用没有对应的首次记录'
    return file, text


def verify_case(java, jar, launcher, base, api_format, scenario):
    workspace = base / (api_format + '-' + scenario) / 'workspace with spaces'
    home = workspace.parent / 'isolated-home'
    workspace.mkdir(parents=True)
    home.mkdir()
    fixture = Fixture(api_format, scenario)
    server = ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler())
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        config = workspace / 'fixture.yaml'
        config.write_text(config_text(server.server_port, api_format, scenario), encoding='utf-8')
        original = config.read_bytes()
        arguments = ['--config', str(config), '--quiet', '--no-color']
        processes = []
        files = []
        if scenario == 'local':
            config.write_text('not: [valid-yaml', encoding='utf-8')
            result, elapsed = invoke(java, jar, launcher, workspace, home, arguments + ['--json', 'logs'])
            processes.append(elapsed)
            assert result.returncode == 0, result.stderr
            report = json.loads(result.stdout)
            assert report['enabled'] is None and report['files'] == []
            assert report['directory'] == str(workspace / '.harness/logs')
            assert not (workspace / '.harness').exists()
            assert not fixture.requests
        else:
            if scenario == 'tools':
                (workspace / 'sample.txt').write_text(PREFIX + 'x' * 2400 + TAIL, encoding='utf-8')
            if scenario == 'blocked':
                (workspace / '.harness').mkdir()
                (workspace / '.harness/logs').write_text('原有占位文件', encoding='utf-8')
            flags = [] if scenario in ('retry', 'partial') else ['--no-stream']
            result, elapsed = invoke(java, jar, launcher, workspace, home, arguments + flags + ['--json', 'run', TASK])
            processes.append(elapsed)
            assert result.returncode == (1 if scenario == 'partial' else 0), result.stderr
            report = json.loads(result.stdout)
            assert report['status'] == ('FAILED' if scenario == 'partial' else 'COMPLETED'), report
            assert config.read_bytes() == original, '执行记录改写了用户配置'
            if scenario in ('disabled', 'blocked'):
                assert report['traceFile'] is None
                if scenario == 'disabled':
                    assert not (workspace / '.harness/logs').exists()
                    assert not result.stderr.strip(), result.stderr
                else:
                    assert result.stderr.count('执行日志记录不可用') == 1
                    assert (workspace / '.harness/logs').read_text(encoding='utf-8') == '原有占位文件'
                assert len(fixture.requests) == 1
            else:
                file, text = trace_contents(workspace, report)
                files.append({'sha256': digest(file.read_bytes()), 'bytes': file.stat().st_size})
                expected = {'tools': 2, 'review': 3, 'retry': 4, 'partial': 1}[scenario]
                assert len(fixture.requests) == expected, '记录功能额外请求了模型'
                assert text.count('## 模型请求 #') == expected
                wire = read_wire_events(text)
                sent = [event['body'] for event in wire if event['kind'] == 'request']
                received = [event['body'] for event in wire if event['kind'] == 'response']
                assert sent == fixture.requests, '还原后的日志请求与服务端收到的报文不一致'
                expected_responses = []
                for response in fixture.responses:
                    body = response['body']
                    for secret in (KEY, REVIEW_KEY, HEADER_VALUE):
                        body = body.replace(secret, '[已脱敏]')
                    try:
                        body = json.loads(body)
                    except ValueError:
                        pass
                    expected_responses.append(body)
                assert received == expected_responses, '还原后的响应与服务端发送内容不一致'
                assert not any(event['truncated'] for event in wire)
                if scenario == 'tools':
                    assert '工具开始' in text and '工具完成' in text and 'trace-read' in text
                    assert PREFIX in text and TAIL in text and '已截断' in text
                    assert TAIL in json.dumps(fixture.requests[-1], ensure_ascii=False)
                    assert text.count(TASK) == 1 and 'new_content' in text
                    assert '输入 17' in text and '缓存输入 9' in text
                    resume, elapsed = invoke(java, jar, launcher, workspace, home, arguments + ['--no-stream', '--json', 'resume', report['session'], FOLLOWUP])
                    processes.append(elapsed)
                    assert resume.returncode == 0, '会话恢复失败：' + resume.stdout + resume.stderr + '; 夹具失败：' + repr(fixture.failures)
                    resumed = json.loads(resume.stdout)
                    assert resumed['session'] == report['session'] and len(fixture.requests) == 3
                    next_file, next_text = trace_contents(workspace, resumed)
                    resumed_wire = read_wire_events(next_text)
                    assert [event['body'] for event in resumed_wire if event['kind'] == 'request'] == fixture.requests[2:]
                    assert [event['body'] for event in resumed_wire if event['kind'] == 'response'] == [json.loads(item['body']) for item in fixture.responses[2:]]
                    assert next_file.parent == file.parent and next_file != file
                    assert FOLLOWUP in next_text and TASK in next_text
                    files.append({'sha256': digest(next_file.read_bytes()), 'bytes': next_file.stat().st_size})
                    # 真实交互入口中的载入、查看日志及回退全部是本地动作。
                    local_input = '/resume ' + report['session'] + '\n/logs\n/rewind 2\n/logs\n/exit\n'
                    interactive, elapsed = invoke(java, jar, launcher, workspace, home, arguments + ['--no-stream'], local_input)
                    processes.append(elapsed)
                    assert interactive.returncode == 0, interactive.stderr
                    assert '执行记录' in interactive.stdout and str(file) in interactive.stdout and str(next_file) in interactive.stdout
                    assert len(fixture.requests) == 3
                    selected, elapsed = invoke(java, jar, launcher, workspace, home, arguments + ['--json', 'logs', report['session']])
                    processes.append(elapsed)
                    listed = json.loads(selected.stdout)
                    assert selected.returncode == 0 and set(listed['files']) == {str(file), str(next_file)}
                    assert len(fixture.requests) == 3
                elif scenario == 'review':
                    assert text.count('- 来源：agent') == 2 and text.count('- 来源：reviewer') == 1
                    assert 'trace-review-model' in text and 'HTTP 响应报文' in text and '审核过程记录标记' in text
                    assert '工具完成' in text and report['review']['requests'] == 1 and report['review']['approved'] == 1
                elif scenario == 'retry':
                    assert text.count('## 模型请求失败 #') == 3 and text.count(TASK) == 1
                    assert 'HTTP 状态码 400' in text and '流式' in text and '非流式' in text
                    intervals = [fixture.times[index + 1] - fixture.times[index] for index in range(3)]
                    assert all(delay >= minimum for delay, minimum in zip(intervals, (0.9, 1.9, 3.9))), intervals
                else:
                    assert PARTIAL in text and '响应读取未完成' in text and '模型请求失败' in text
                    assert '## 模型回复 #' not in text
            assert report['traceFile'] is None or Path(report['traceFile']).parent.name == report['session']
        assert not fixture.failures, fixture.failures
        request_hashes = [digest(json.dumps(request, ensure_ascii=False, sort_keys=True).encode('utf-8')) for request in fixture.requests]
        return {'apiFormat': api_format, 'scenario': scenario, 'passed': True, 'modelRequests': len(fixture.requests),
                'wireRoundTripVerified': scenario in ('tools', 'review', 'retry', 'partial'),
                'paths': fixture.paths, 'requestSha256': request_hashes, 'processElapsedMs': processes, 'traceFiles': files,
                'retryIntervalsSeconds': [round(fixture.times[i + 1] - fixture.times[i], 3) for i in range(3)] if scenario == 'retry' else []}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='全局启动器的绝对路径；省略时运行源码 JAR')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/execution-trace-source-smoke.json')
    options = parser.parse_args()
    java = java_executable()
    launcher = options.launcher
    if launcher:
        if not launcher.is_absolute() or not launcher.is_file():
            raise ValueError('--launcher 必须是现有启动器的绝对路径')
        launcher = launcher.resolve()
        library = launcher.parent.parent / 'lib'
        jar = library / (library / 'current-runtime.txt').read_text(encoding='ascii').strip()
    else:
        jar = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
    assert jar.is_file(), '先构建带依赖运行包'
    required = ['dev/codeharness/cli/TraceRecorder.class', 'dev/codeharness/cli/TraceFiles.class',
                'dev/codeharness/cli/trace/ExecutionTrace.class', 'dev/codeharness/cli/trace/TraceFile.class',
                'dev/codeharness/cli/trace/TraceRedactor.class', 'dev/codeharness/cli/trace/WirePayloads.class',
                'dev/codeharness/providers/HttpExchangeTrace.class', 'dev/codeharness/cli/tools/UnifiedDiff.class']
    with zipfile.ZipFile(jar) as archive:
        assert all(name in archive.namelist() for name in required), '运行包缺少必要类'
    cases = [('chat', 'tools'), ('responses', 'tools'), ('chat', 'review'), ('chat', 'retry'),
             ('responses', 'retry'), ('chat', 'partial'), ('chat', 'disabled'), ('chat', 'blocked'), ('chat', 'local')]
    started = time.monotonic()
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='execution-trace-smoke-', dir=target) as temporary:
        records = [verify_case(java, jar, launcher, Path(temporary).resolve(), api_format, scenario) for api_format, scenario in cases]
    summary = {'passed': True, 'completedAtUtc': datetime.now(timezone.utc).isoformat(), 'jar': str(jar),
               'jarSha256': digest(jar.read_bytes()), 'launcher': str(launcher) if launcher else None,
               'scope': '真实 CLI 进程；仅 localhost 模型夹具，独立 home 和工作区；未加载真实用户私有配置。',
               'cases': records, 'totalCases': len(records), 'modelRequests': sum(record['modelRequests'] for record in records),
               'elapsedMs': round((time.monotonic() - started) * 1000, 3), 'requiredClasses': required}
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('执行记录验收通过：' + str(len(records)) + ' 个场景，' + str(summary['modelRequests']) + ' 次 localhost 请求。')
    print('证据：' + str(options.output.resolve()))


if __name__ == '__main__':
    main()
