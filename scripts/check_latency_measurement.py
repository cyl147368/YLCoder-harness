"""使用真实 jar 和 localhost 验证首个可见字节的测量，绝不请求外部服务。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
import subprocess
import threading
from unittest.mock import patch
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from measure_latency import PHRASE, measure_sample, parse_cli_usage_summary, redact


def check_usage_summaries():
    """无 Java、无网络的双语旧汇总与当前四行底栏边界对照。"""
    english = 'Session: fixture-1 | COMPLETED | 1 calls | 15 tokens | 2 ms'
    chinese = '会话：fixture-1 | COMPLETED | 1 次调用 | 15 tokens | 2 ms'
    compact = '\n'.join([
        '  ── 状态 · 已回复 ──',
        '  模型 latency-local-fixture · 厂商 127.0.0.1',
        '  本轮  输入 token 12 · 输出 token 3',
        '  缓存 token 6 · 缓存命中率 50.0%',
        '  已回复 · 1次主模型/0次审核 · 未调用工具 · 0.2 秒',
    ])
    positives = [(line + ending, 15) for line in (english, chinese) for ending in ('', '\n', '\r\n')]
    positives += [(line.replace('15 tokens', '0 tokens'), 0) for line in (english, chinese)]
    positives += [('其他输出\n' + chinese + '\n尾部输出', 15)]
    positives += [(compact + ending, 15) for ending in ('', '\n', '\r\n')]
    positives += [(compact.replace('\n', '\r\n'), 15),
                  ('其他输出\n' + compact + '\n  继续：ylc resume fixture-1\n', 15),
                  (compact.replace('输入 token 12', '输入 token 0').replace('输出 token 3', '输出 token 0'), 0),
                  (compact.replace('输入 token 12', '输入 token 1,234').replace('输出 token 3', '输出 token 56,789'), 58023),
                  (compact.replace('缓存 token 6', '缓存 token —（未报告）')
                          .replace('50.0%', '—（缓存用量未报告）'), 15)]
    plain = compact.replace('── 状态 · 已回复 ──', '已回复')
    positives += [(plain + ending, 15) for ending in ('', '\n', '\r\n')]
    positives += [(plain.replace('输入 token 12', '输入 token 0').replace('输出 token 3', '输出 token 0'), 0)]
    for text, expected in positives:
        assert parse_cli_usage_summary(text.encode('utf-8')) == expected, text
    negatives = [
        english.replace('1 calls', '2 calls'), chinese.replace('1 次调用', '2 次调用'),
        english.replace('1 calls', '0 calls'), chinese.replace('1 次调用', '0 次调用'),
        english.replace('1 calls', '1 次调用'), chinese.replace('1 次调用', '1 calls'),
        chinese.replace('会话：', '会话: '), chinese.replace('15 tokens', '-15 tokens'),
        english.replace('15 tokens', '15.5 tokens'), chinese.replace('15 tokens', 'unknown tokens'),
        english.replace('COMPLETED', 'completed'), chinese.replace('fixture-1', 'fixture/1'),
        ' ' + english, chinese + ' extra', chinese.replace('2 ms', '-2 ms'),
        english + '\n' + chinese,
        compact + '\n' + compact,
        compact + '\n' + english,
        compact.replace('1次主模型/0次审核', '2次主模型/0次审核'),
        compact.replace('1次主模型/0次审核', '0次主模型/0次审核'),
        compact.replace('1次主模型/0次审核', '1次主模型/1次审核'),
        compact.replace('未调用工具', '1次工具'),
        compact.replace('输入 token 12', '输入 token —（未报告）'),
        compact.replace('输出 token 3', '输出 token —（未报告）'),
        compact.replace('输入 token 12', '输入 token 12（部分）'),
        compact.replace('输出 token 3', '输出 token 3（部分）'),
        compact.replace('输入 token 12', '输入 token -12'),
        compact.replace('输出 token 3', '输出 token 3.5'),
        compact.replace('输入 token 12', '输入 token 1,23'),
        compact.replace('输出 token 3', '输出 token 0,003'),
        compact.replace('输入 token 12', '输入 token １２'),
        compact.replace('  模型 latency-local-fixture · 厂商 127.0.0.1\n', ''),
        compact.replace('  缓存 token 6 · 缓存命中率 50.0%\n', ''),
        compact.replace('  已回复 · 1次主模型', '  未完成 · 1次主模型'),
        compact + ' extra',
    ]
    negatives += [plain + '\n' + compact, plain + '\n' + plain, plain + '\n' + english,
                  plain.replace('输入 token 12', '输入 token — 未报告'),
                  plain.replace('输出 token 3', '输出 token 3（部分）'),
                  plain.replace('  已回复 · 1次主模型', '  未完成 · 1次主模型')]
    for text in negatives:
        assert parse_cli_usage_summary(text.encode('utf-8')) is None, text
    return {'positive': len(positives), 'negative': len(negatives), 'languages': ['en', 'zh'],
            'formats': ['legacy-single-line', 'compact-four-row-status']}


class Fixture:
    def __init__(self, mode, artificial):
        self.mode, self.artificial = mode, artificial
        self.release = threading.Event()
        self.errors = []
        self.requests = 0

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    self.connection.settimeout(10)
                    assert self.path == '/v1/chat/completions'
                    assert self.headers.get('Authorization') == 'Bearer ' + fixture.artificial
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    fixture.requests += 1
                    assert fixture.requests == 1
                    assert body['max_tokens'] == 64
                    assert body.get('stream', False) == (fixture.mode != 'sync')
                    if fixture.mode in ('early', 'unfinished'):
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/event-stream')
                        self.end_headers()

                        def event(payload):
                            value = payload if isinstance(payload, str) else json.dumps(payload)
                            self.wfile.write(('data: ' + value + '\n\n').encode())
                            self.wfile.flush()

                        event({'choices': [{'index': 0, 'delta': {'content': PHRASE}}]})
                        if fixture.mode == 'early':
                            assert fixture.release.wait(8), '缺少完成事件释放'
                        event({'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]})
                        if fixture.mode == 'early':
                            event({'choices': [], 'usage': {'prompt_tokens': 12, 'completion_tokens': 3}})
                            event('[DONE]')
                    else:
                        message = {'role': 'assistant', 'content': PHRASE}
                        finish = 'stop'
                        if fixture.mode == 'tool':
                            message = {'role': 'assistant', 'content': '', 'tool_calls': [{
                                'id': 'forbidden-mutation', 'type': 'function', 'function': {'name': 'write_file',
                                'arguments': json.dumps({'path': 'must-not-exist.txt', 'content': 'mutation'})}}]}
                            finish = 'tool_calls'
                        payload = {'choices': [{'message': message, 'finish_reason': finish}]}
                        if fixture.mode != 'missing':
                            payload['usage'] = ({'prompt_tokens': 0, 'completion_tokens': 0}
                                                if fixture.mode == 'zero' else {'prompt_tokens': 12})
                            if fixture.mode not in ('zero', 'partial'):
                                payload['usage']['completion_tokens'] = 3
                        response = b'{malformed' if fixture.mode == 'malformed' else json.dumps(payload).encode()
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(response)))
                        self.end_headers()
                        self.wfile.write(response)
                except Exception as failure:
                    fixture.errors.append(type(failure).__name__)
                    self.close_connection = True

        return Provider


def main():
    summary_controls = check_usage_summaries()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    variable = 'HARNESS_LATENCY_LOCAL_' + uuid.uuid4().hex.upper()
    artificial = 'artificial-local-' + uuid.uuid4().hex
    rows = []
    os.environ[variable] = artificial
    try:
        for mode in ('early', 'sync', 'zero', 'missing', 'partial', 'unfinished', 'malformed', 'tool'):
            fixture = Fixture(mode, artificial)
            visible = threading.Event()
            with ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler()) as server:
                threading.Thread(target=server.serve_forever, daemon=True).start()
                try:
                    with ThreadPoolExecutor(max_workers=1) as executor:
                        future = executor.submit(measure_sample, args.java,
                            f'http://127.0.0.1:{server.server_port}/v1', 'latency-local-fixture', variable,
                            mode != 'sync', 12, visible.set)
                        if mode == 'early':
                            assert visible.wait(8), '在保持完成事件之前没有真实 CLI 字节'
                            assert not future.done(), '保持完成事件期间测量已提前结束'
                            fixture.release.set()
                        row = future.result(timeout=20)
                    assert not fixture.errors, '夹具协议检查失败'
                    assert fixture.requests == 1, 'Unexpected retry'
                    assert row['checks']['workspaceAllowlist'], '单次迭代探测中出现意外修改'
                    if mode in ('early', 'sync', 'zero', 'missing', 'partial'):
                        assert row['passed'], '有效响应未通过验收'
                        assert row['firstVisibleTextMs'] is not None
                        assert 0 < row['firstVisibleTextMs'] <= row['processCompletionMs']
                        if mode in ('missing', 'partial'):
                            assert row['usage'] is None and row['usageAvailability'] == 'unavailable'
                        else:
                            assert row['usage']['totalTokens'] == (0 if mode == 'zero' else 15)
                    else:
                        assert not row['passed'], '不完整或工具响应被错误地判为通过'
                    if mode == 'tool':
                        assert row['status'] == 'MAX_ITERATIONS'
                        assert not row['checks']['zeroTools']
                        assert row['firstVisibleTextMs'] is None, '不应测得工具响应的可见回答'
                    rows.append({'fixture': mode, 'passed': row['passed'], 'status': row['status'],
                                 'firstVisibleTextMs': row['firstVisibleTextMs'],
                                 'processCompletionMs': row['processCompletionMs'],
                                 'errorCategory': row['errorCategory'], 'requests': fixture.requests,
                                 'usage': row['usage'], 'usageAvailability': row['usageAvailability'],
                                 'workspaceAllowlist': row['checks']['workspaceAllowlist']})
                finally:
                    fixture.release.set()
                    server.shutdown()
    finally:
        os.environ.pop(variable, None)
    escaped = 'artificial"\\\n'
    assert json.loads(json.dumps(redact({escaped: [escaped]}, escaped))) == {'[redacted]': ['[redacted]']}

    class CleanupFailureChild:
        stdout, stderr = io.BytesIO(), io.BytesIO()
        returncode = None

        def wait(self, timeout):
            raise subprocess.TimeoutExpired('synthetic-child', timeout)

        def poll(self):
            return None

        def kill(self):
            raise OSError('人工私密异常详情不得出现在报告中')

    with patch('measure_latency.subprocess.Popen', return_value=CleanupFailureChild()):
        failed = measure_sample(args.java, 'http://127.0.0.1:1/v1', 'local', variable, True, 1)
    assert not failed['passed'] and failed['cleanupFailure'] and failed['errorCategory'] == 'sample_timeout'

    def inspection_failure(*parameters):
        result = parameters[-1]
        result['firstVisibleTextMs'] = 1.25
        raise OSError('人工私密异常详情不得出现在报告中')

    with patch('measure_latency._measure_sample', side_effect=inspection_failure):
        failed = measure_sample(args.java, 'http://127.0.0.1:1/v1', 'local', variable, True, 1)
    assert not failed['passed'] and failed['errorCategory'] == 'measurement_io_failure'
    assert failed['firstVisibleTextMs'] == 1.25
    assert 'private detail' not in json.dumps(failed)
    print(json.dumps({'status': 'PASS', 'scope': '真实打包 CLI，仅使用 localhost 合成响应',
                      'controls': rows, 'bilingualUsageSummaryControls': summary_controls, 'escapedArtificialRedaction': True,
                      'syntheticCleanupFailureRetained': True, 'syntheticInspectionFailureRetained': True}, indent=2))


if __name__ == '__main__':
    main()
