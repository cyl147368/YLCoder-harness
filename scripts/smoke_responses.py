"""用 localhost 验证 Responses 协议、审核、真实 SSE、会话恢复及流式回退；不调用真实厂商。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
KEY = 'responses-offline-fixture-key'
TASK = '检查当前日期，完成后简短回答。'
FOLLOWUP = '继续根据刚才的日期结果回答。'
ASSISTANT_PROSE = 'RESPONSES_ASSISTANT_TEXT_MUST_NOT_ENTER_REVIEW'
REVIEW_REASON = 'RESPONSES_REVIEW_REASON_MUST_NOT_ENTER_SESSION'
FINAL_TEXT = 'Responses 日期查询已完成。'
RESUMED_TEXT = 'Responses 会话已恢复，日期结果仍在。'
MAIN_MODEL = 'responses-main-fixture'
REVIEW_MODEL = 'responses-review-fixture'
ENVIRONMENT_HOOKS = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
                     'JVM_CONFIG_MAVEN_PROPS', 'JAVACMD', 'JAVA_HOME', 'M2_HOME'}
CASES = (
    ('config-responses-sse', 'openai-compatible', 'responses', False, False, False, True),
    ('cli-overrides-chat', 'openai-compatible', 'chat', True, False, False, True),
    ('cli-responses-resume', 'openai-compatible', None, True, True, False, True),
    ('legacy-responses', 'openai-responses', None, False, False, False, False),
    ('responses-stream400', 'openai-compatible', 'responses', False, False, True, True),
)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding='utf-8')


def java_executable():
    configured = os.environ.get('JAVA_HOME')
    candidate = Path(configured) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if configured else None
    executable = candidate if candidate and candidate.is_file() else shutil.which('java')
    if not executable:
        raise RuntimeError('本地 Responses 验收需要已安装的 Java 21')
    return Path(executable).resolve()


def invoke(java, launcher, workspace, user_home, arguments, input_text=''):
    """真实启动 CLI，显式隔离 home；Windows 用应用参数文件保留中文与空格。"""
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
    run = subprocess.run(launch + arguments, cwd=workspace, env=environment, input=input_text,
                         capture_output=True, text=True, encoding='utf-8', timeout=90)
    combined = run.stdout + run.stderr
    assert KEY not in combined, '验收凭据出现在 CLI 输出中'
    assert REVIEW_REASON not in combined, '分类器原始理由出现在 CLI 输出中'
    assert run.returncode == 0, (run.returncode, run.stdout, run.stderr)
    return run


def message(identifier, content):
    return {'type': 'message', 'id': identifier, 'role': 'assistant', 'status': 'completed',
            'content': [{'type': 'output_text', 'text': content}]}


def reasoning(index):
    return {'type': 'reasoning', 'id': f'reasoning-{index}', 'summary': [],
            'encrypted_content': f'opaque-encrypted-reasoning-{index}'}


def user_contents(items):
    return [item['content'] for item in items if item.get('role') == 'user']


def assert_replay(items, completed_turns):
    """检查真实请求中的原始输出项和工具结果，不只检查持久化文件含有某个字符串。"""
    actual_reasoning = [item for item in items if item.get('type') == 'reasoning']
    assert actual_reasoning == [reasoning(index) for index in range(1, completed_turns + 1)], actual_reasoning
    identifiers = [item['id'] for item in items if item.get('id')]
    assert len(identifiers) == len(set(identifiers)), '恢复时重复回放了 Responses 输出 ID'
    calls = [item for item in items if item.get('type') == 'function_call']
    outputs = [item for item in items if item.get('type') == 'function_call_output']
    assert len(calls) == len(outputs) == 1, (calls, outputs)
    assert calls[0]['call_id'] == outputs[0]['call_id'] == 'date-call'
    assert calls[0]['name'] == 'run_command' and json.loads(calls[0]['arguments']) == {'command': 'date'}
    assert outputs[0]['output'].startswith('退出码：0\n'), outputs[0]
    assert '未获批准' not in outputs[0]['output'], outputs[0]
    if completed_turns >= 2:
        assert any(item.get('type') == 'message' and item['content'][0].get('text') == FINAL_TEXT for item in items)


class Fixture:
    def __init__(self, stream400, streaming):
        self.stream400, self.streaming = stream400, streaming
        self.main, self.review, self.errors, self.times = [], [], [], []
        self.chat_requests = self.other_requests = self.successful_main = self.sse_responses = self.sse_events = 0

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, status, payload):
                encoded = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def stream(self, payload):
                fixture.sse_responses += 1
                text_item = payload['output'][1]
                text = text_item['content'][0]['text']
                midpoint = max(1, len(text) // 2)
                events = [{'type': 'response.created', 'sequence_number': 0,
                           'response': {'id': payload['id'], 'status': 'in_progress'}}]
                for part in (text[:midpoint], text[midpoint:]):
                    events.append({'type': 'response.output_text.delta', 'item_id': text_item['id'],
                                   'output_index': 1, 'content_index': 0, 'delta': part,
                                   'sequence_number': len(events)})
                if fixture.successful_main == 1:
                    for part in ('{"command":', '"date"}'):
                        events.append({'type': 'response.function_call_arguments.delta', 'item_id': 'date-function',
                                       'output_index': 2, 'delta': part, 'sequence_number': len(events)})
                events.append({'type': 'response.completed', 'sequence_number': len(events), 'response': payload})
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'close')
                self.end_headers()
                for event in events:
                    self.wfile.write(('event: ' + event['type'] + '\ndata: '
                                      + json.dumps(event, ensure_ascii=False) + '\n\n').encode('utf-8'))
                    self.wfile.flush()
                    fixture.sse_events += 1
                    # 每个增量独立刷入真实 HTTP 流，避免把完整 JSON 冒充流式验收。
                    time.sleep(.01)

            def do_POST(self):
                try:
                    if self.path.endswith('/chat/completions'):
                        fixture.chat_requests += 1
                    if self.path != '/v1/responses':
                        fixture.other_requests += 1
                        self.reply(404, {'error': {'message': 'local fixture only supports Responses'}})
                        return
                    assert self.headers.get('Authorization') == 'Bearer ' + KEY, '未继承本地模拟凭据'
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    assert isinstance(body.get('input'), list) and 'messages' not in body, body
                    assert body.get('store') is False and 'reasoning.encrypted_content' in body.get('include', []), body
                    reviewer = body['model'] == REVIEW_MODEL
                    if reviewer:
                        fixture.review.append(body)
                        assert len(fixture.review) == 1, '审核被重复调用'
                        assert not body.get('stream') and not body.get('tools'), body
                        assert body['max_output_tokens'] == 192, body
                        users = user_contents(body['input'])
                        assert len(users) == 1, users
                        review_input = json.loads(users[0])
                        assert review_input['command'] == 'date', review_input
                        assert any(item.get('role') == 'user' and item.get('content') == TASK
                                   for item in review_input['context']), review_input
                        assert ASSISTANT_PROSE not in json.dumps(body, ensure_ascii=False)
                        output = [message('review-message', json.dumps({'decision': 'approve', 'reason': REVIEW_REASON}))]
                    else:
                        assert body['model'] == MAIN_MODEL and body.get('tools'), body
                        fixture.main.append(body)
                        fixture.times.append(time.monotonic())
                        if fixture.stream400 and len(fixture.main) == 1:
                            assert body.get('stream') is True, body
                            self.reply(400, {'error': {'message': 'stream unsupported ' + KEY,
                                                       'code': 'invalid_parameter', 'param': 'stream'}})
                            return
                        if fixture.stream400:
                            assert not body.get('stream'), '回退后应保持 Responses 非流式协议'
                            if len(fixture.main) == 2:
                                assert {key: value for key, value in fixture.main[0].items() if key != 'stream'} == body
                        else:
                            assert bool(body.get('stream')) == fixture.streaming, body
                        turn = fixture.successful_main + 1
                        assert turn <= 3, '出现意外的额外模型请求'
                        assert user_contents(body['input']) == ([TASK, FOLLOWUP] if turn == 3 else [TASK])
                        if turn > 1:
                            assert_replay(body['input'], turn - 1)
                        output = [reasoning(turn), message(f'main-message-{turn}',
                                  ASSISTANT_PROSE if turn == 1 else FINAL_TEXT if turn == 2 else RESUMED_TEXT)]
                        if turn == 1:
                            output.append({'type': 'function_call', 'id': 'date-function', 'call_id': 'date-call',
                                           'name': 'run_command', 'arguments': '{"command":"date"}', 'status': 'completed'})
                        fixture.successful_main += 1
                    payload = {'id': 'response-review' if reviewer else f'response-main-{fixture.successful_main}',
                               'status': 'completed', 'output': output,
                               'usage': {'input_tokens': 11 if reviewer else 13, 'output_tokens': 4 if reviewer else 3,
                                         'input_tokens_details': {'cached_tokens': 2 if reviewer else 5}}}
                    if body.get('stream'):
                        self.stream(payload)
                    else:
                        self.reply(200, payload)
                except Exception as failure:
                    fixture.errors.append(repr(failure).replace(KEY, '[验收凭据]'))
                    self.reply(500, {'error': {'message': 'local Responses fixture assertion failed'}})

        return Provider


def assert_report(report, fixture):
    assert report['status'] == 'COMPLETED' and report['iterations'] == 2, report
    assert report['provider'] == 'openai-responses' and report['model'] == MAIN_MODEL, report
    assert FINAL_TEXT in json.dumps(report, ensure_ascii=False)
    review = report['review']
    assert review['requests'] == review['approved'] == 1, review
    assert review['manual'] == review['localApprovals'] == 0, review
    assert review['usage'] == {'inputTokens': 11, 'outputTokens': 4, 'cachedInputTokens': 2}, review
    assert report['agentUsage'] == {'inputTokens': 26, 'outputTokens': 6, 'cachedInputTokens': 10}, report
    expected = {'inputTokens': 37, 'outputTokens': 10, 'cachedInputTokens': 12}
    assert report['usage'] == expected, report
    usage = report['usageReport']
    assert usage['requests'] == 3 + int(fixture.stream400), usage
    assert usage['total'] == expected, usage
    assert usage['inputReportedRequests'] == usage['outputReportedRequests'] == usage['cacheReportedRequests'] == 3, usage
    assert math.isclose(usage['reportedCacheHitRatePercent'], 1200 / 37, rel_tol=1e-9), usage
    if fixture.stream400:
        assert usage['cacheHitRatePercent'] is None, '失败请求没有报告用量，不能展示完整缓存率'
    else:
        assert math.isclose(usage['cacheHitRatePercent'], 1200 / 37, rel_tol=1e-9), usage


def checkpoint(workspace, session, resumed):
    files = list((workspace / '.harness/sessions').glob('*.json'))
    assert len(files) == 1, '恢复应更新原会话，不应另建会话'
    saved = json.loads(files[0].read_text(encoding='utf-8'))
    assert saved['id'] == session and saved['provider'] == 'openai-responses', saved
    assert saved['status'] == 'COMPLETED', saved
    users = [item['content'] for item in saved['transcript'] if item['role'] == 'USER']
    assert users == ([TASK, FOLLOWUP] if resumed else [TASK]), users
    items = []
    for item in saved['transcript']:
        metadata = item.get('providerMetadata', {}).get('openai.responses.outputItems')
        if metadata is not None:
            items.extend(json.loads(metadata))
    actual = [item for item in items if item.get('type') == 'reasoning']
    assert actual == [reasoning(index) for index in range(1, 4 if resumed else 3)], actual
    for stored in (workspace / '.harness').rglob('*'):
        if stored.is_file():
            content = stored.read_text(encoding='utf-8', errors='replace')
            assert KEY not in content and REVIEW_REASON not in content, '凭据或审核原始理由进入持久化数据'
    return saved


def verify(launcher=None):
    java = java_executable()
    if launcher:
        if not launcher.is_absolute() or not launcher.is_file():
            raise ValueError('--launcher 必须是已安装 ylc 启动器的绝对文件路径')
        launcher = launcher.resolve()
    elif not JAR.is_file():
        raise RuntimeError('找不到源码打包 JAR；请先构建或通过 --launcher 指定已安装的启动器')
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    records = []
    with tempfile.TemporaryDirectory(prefix='responses-smoke-', dir=target) as temporary:
        base = Path(temporary).resolve()
        for name, provider, api_format, override, resume, stream400, streaming in CASES:
            workspace, user_home = base / name / 'workspace', base / name / 'home'
            workspace.mkdir(parents=True)
            user_home.mkdir(parents=True)
            fixture = Fixture(stream400, streaming)
            with ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler()) as server:
                serving = threading.Thread(target=server.serve_forever, daemon=True)
                serving.start()
                config = workspace / 'offline.yaml'
                write(config, f'provider: {provider}\nmodel: {MAIN_MODEL}\napi-key: {KEY}\napi-key-env: ""\n'
                      + f'base-url: http://127.0.0.1:{server.server_port}/v1\n'
                      + (f'api-format: {api_format}\n' if api_format else '')
                      + 'web:\n  enabled: false\npermissions:\n  mode: review\n  reviewer:\n'
                      + f'    model: {REVIEW_MODEL}\n')
                original_config = config.read_bytes()
                arguments = ['--config', str(config), '--workspace', str(workspace), '--no-color', '--quiet']
                if not streaming:
                    arguments.append('--no-stream')
                try:
                    run = invoke(java, launcher, workspace, user_home,
                                 arguments + (['--api-format', 'responses'] if override else []) + ['--json', 'run', TASK])
                    assert not fixture.errors, fixture.errors
                    assert fixture.chat_requests == fixture.other_requests == 0, '请求了非 Responses 端点'
                    report = json.loads(run.stdout)
                    assert_report(report, fixture)
                    assert len(fixture.main) == 2 + int(stream400) and len(fixture.review) == 1
                    saved = checkpoint(workspace, report['session'], False)
                    if resume:
                        # 新进程不再指定协议：只能依靠原检查点恢复有效的 Responses provider。
                        continued = invoke(java, launcher, workspace, user_home, arguments,
                                           '/resume ' + report['session'] + '\n' + FOLLOWUP + '\n/exit\n')
                        assert FINAL_TEXT in continued.stdout and RESUMED_TEXT in continued.stdout, continued.stdout
                        assert len(fixture.main) == 3 and len(fixture.review) == 1, '恢复不应重复执行和审核旧工具'
                        saved = checkpoint(workspace, report['session'], True)
                    assert not fixture.errors, fixture.errors
                    assert fixture.chat_requests == fixture.other_requests == 0
                    assert config.read_bytes() == original_config, 'CLI 协议选择不能改写配置'
                    assert fixture.sse_responses == (fixture.successful_main if streaming and not stream400 else 0)
                    delay = fixture.times[1] - fixture.times[0] if stream400 else None
                    if stream400:
                        assert delay >= .95, ('第一次回退应等待 1 秒', delay)
                finally:
                    server.shutdown()
                    serving.join(5)
            records.append({'case': name, 'status': 'PASS', 'mainRequests': len(fixture.main),
                            'reviewRequests': len(fixture.review), 'chatCompletionsRequests': fixture.chat_requests,
                            'sseResponses': fixture.sse_responses, 'sseEvents': fixture.sse_events,
                            'streamFallbackDelaySeconds': round(delay, 3) if delay is not None else None,
                            'savedProvider': saved['provider'], 'restoredSameSession': resume,
                            'encryptedReasoningItems': 3 if resume else 2, 'usage': report['usage'],
                            'usageReport': report['usageReport'], 'review': report['review']})
    return {'status': 'PASS', 'cases': records, 'chatCompletionsRequests': 0,
            'localhostRequests': sum(item['mainRequests'] + item['reviewRequests'] for item in records),
            'scope': '独立 CLI 进程和隔离 home；仅 localhost Responses 模拟服务；仅查询日期，不运行 Maven，不调用真实厂商。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='已安装 ylc 启动器的绝对路径；省略时验证源码打包 JAR')
    parser.add_argument('--result', type=Path, help='保存不含凭据的 JSON 验收证据')
    options = parser.parse_args()
    result = verify(options.launcher)
    if options.result:
        write(options.result, json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
