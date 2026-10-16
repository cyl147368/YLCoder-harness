"""仅使用 localhost，验证评估器显式启用路径中的精确预置检查点。"""
import argparse
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from evaluate_coding import JAR, TASKS, run_case, verify


class SeedFixture:
    def __init__(self, task, variant, artificial):
        self.task, self.variant, self.artificial = task, variant, artificial
        self.metrics = []
        self.errors = []

    def handler(self):
        fixture = self

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    self.connection.settimeout(10)
                    assert self.path == '/v1/chat/completions', 'wrong route'
                    assert self.headers.get('Authorization') == 'Bearer ' + fixture.artificial, '缺少人工认证头'
                    length = int(self.headers['Content-Length'])
                    assert 0 < length < 2_000_000, 'request bound'
                    body = json.loads(self.rfile.read(length))
                    assert body.get('stream') is True, 'stream flag'
                    assert body.get('max_tokens') == 700, '配置的输出上限'
                    assert body.get('model') == 'seeded-local-fixture', 'model override'
                    assert 'stream_options' not in body, '通用网关选项'
                    messages = body['messages']
                    pending, seen, ids = [], set(), []
                    roles = []
                    for message in messages:
                        role = message['role']
                        roles.append(role)
                        if role == 'tool':
                            assert pending and message['tool_call_id'] == pending.pop(0), '有序结果配对'
                        else:
                            assert not pending, '之前调用不完整'
                            calls = message.get('tool_calls', [])
                            assert not calls or role == 'assistant', '非 assistant 消息含调用'
                            for call in calls:
                                identity = call['id']
                                assert identity not in seen, '调用 ID 重复'
                                seen.add(identity)
                                pending.append(identity)
                                ids.append(identity)
                    assert not pending, '缺少最终结果'
                    assert roles.count('system') == 1 and roles.count('user') == 2, '必需指令丢失'
                    number = len(fixture.metrics) + 1
                    assert number <= 2, '发生意外重试或请求'
                    if number == 1:
                        assert messages[-1]['role'] == 'user', '首个请求的最后一条消息必须是后续指令'
                        source_results = [m for m in messages if m['role'] == 'tool' and m['tool_call_id'] == 'seed-source']
                        assert len(source_results) == 1, '缺少预置源码'
                        assert source_results[0]['content'] == fixture.task['source'], 'source altered'
                        assert fixture.task['prompt'] in [m['content'] for m in messages if m['role'] == 'user'], '缺少原始任务'
                        if fixture.variant == 'baseline':
                            assert len(messages) == 29 and len(ids) == 13, '基线首次请求数量'
                            assert ids == ['seed-' + str(i) for i in range(1, 13)] + ['seed-source'], 'seed order'
                        else:
                            assert len(messages) < 29 and 1 <= len(ids) < 13, '压缩未移除历史'
                            assert ids[-1] == 'seed-source', '最近的预置周期发生变化'
                        message = {'role': 'assistant', 'content': '', 'tool_calls': [{
                            'id': 'fixture-repair', 'type': 'function', 'function': {'name': 'edit_file',
                            'arguments': json.dumps({'path': 'Subject.java', 'old_text': fixture.task['old'],
                                                     'new_text': fixture.task['new']})}}]}
                    else:
                        repairs = [m for m in messages if m['role'] == 'tool' and m['tool_call_id'] == 'fixture-repair']
                        assert len(repairs) == 1 and repairs[0]['content'].startswith(('Edited "Subject.java":', '已编辑 "Subject.java":')), '缺少成功修改结果'
                        assert ids[-1] == 'fixture-repair', 'repair ordering'
                        message = {'role': 'assistant', 'content': '已应用已知夹具修复。'}
                    fixture.metrics.append({'request': number, 'messageCount': len(messages),
                                            'toolCycleCount': len(ids), 'orderedPairsValid': True,
                                            'seededSourceExact': True if number == 1 else None})
                    # JSON 回退用于验证请求中的 stream 标志，无需单独的解析器夹具。
                    response = json.dumps({'choices': [{'message': message,
                        'finish_reason': 'tool_calls' if number == 1 else 'stop'}]}).encode('utf-8')
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(response)))
                    self.end_headers()
                    self.wfile.write(response)
                except Exception as failure:
                    fixture.errors.append(str(failure) if isinstance(failure, AssertionError) else type(failure).__name__)
                    self.send_response(422)
                    self.send_header('Content-Length', '0')
                    self.end_headers()

        return Provider


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    options = parser.parse_args()
    assert JAR.is_file(), '请先构建打包 CLI'
    task = next(task for task in TASKS if task['id'] == 'inclusive-range')
    with tempfile.TemporaryDirectory(prefix='seeded-checker-control-') as temporary:
        workspace = Path(temporary)
        source = workspace / 'Subject.java'
        source.write_text(task['source'], encoding='utf-8')
        buggy = verify(workspace, task, options.java, options.javac)
        source.write_text(task['source'].replace(task['old'], task['new']), encoding='utf-8')
        repaired = verify(workspace, task, options.java, options.javac)
        assert not buggy['passed'] and repaired['passed'], '独立对照失败'
    variable = 'HARNESS_LOCAL_SEEDED_' + uuid.uuid4().hex.upper()
    artificial = 'artificial-local-only-' + uuid.uuid4().hex
    rows = []
    os.environ[variable] = artificial
    try:
        for variant in ('baseline', 'compact'):
            fixture = SeedFixture(task, variant, artificial)
            with tempfile.TemporaryDirectory(prefix='seeded-local-config-') as temporary:
                config = Path(temporary) / 'fixture.yaml'
                with ThreadingHTTPServer(('127.0.0.1', 0), fixture.handler()) as server:
                    threading.Thread(target=server.serve_forever, daemon=True).start()
                    try:
                        config.write_text('provider: openai-compatible\nmodel: seeded-local-fixture\n'
                                          f'base-url: http://127.0.0.1:{server.server_port}/v1\n'
                                          f'api-key-env: {variable}\ntimeout-seconds: 10\nagent:\n'
                                          '  max-output-tokens: 700\npermissions:\n'
                                          '  allow-commands: false\n  allow-mcp-tools: false\n', encoding='utf-8')
                        args = SimpleNamespace(live=True, config=str(config), provider='openai-compatible',
                            model='seeded-local-fixture', credential_env=variable, java=options.java, javac=options.javac,
                            baseline_budget=48000, compact_budget=4000, max_iterations=5, timeout=40, no_stream=False)
                        row = run_case(args, task, variant, 1)
                        assert not fixture.errors, 'localhost 结构验证失败：' + ', '.join(fixture.errors)
                        assert row['passed'] and row['status'] == 'COMPLETED' and row['quality']['passed'], '运行或验证器失败'
                        assert row['retainedToolNamesAfterSeed'] == ['edit_file'], '压缩后的轨迹排除了新修复'
                        assert row['retainedToolFailuresAfterSeed'] == 0, '保留了修复失败'
                        assert len(fixture.metrics) == 2 and row['iterations'] == 2, '工作流请求数'
                        rows.append({'variant': variant, 'status': row['status'], 'independentQuality': row['quality']['passed'],
                                     'retainedToolNamesAfterSeed': row['retainedToolNamesAfterSeed'],
                                     'retainedToolFailuresAfterSeed': row['retainedToolFailuresAfterSeed'],
                                     'requests': fixture.metrics, 'sourceSha256': row['sourceSha256']})
                    finally:
                        server.shutdown()
    finally:
        os.environ.pop(variable, None)
    assert rows[0]['sourceSha256'] == rows[1]['sourceSha256'], '成对修复不一致'
    print(json.dumps({'status': 'PASS', 'scope': 'localhost seeded-path structural acceptance; no external model calls',
                      'checkerControls': {'rejectsBuggy': True, 'acceptsRepair': True}, 'runs': rows}, indent=2))


if __name__ == '__main__':
    main()
