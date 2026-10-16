"""通过 localhost 和真实 CLI 验证用量面板、缓存口径及输出通道。"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
MODEL = 'dashboard-fixture'
PROVIDER = 'openai-compatible'
ENDPOINT_PATH = '/dashboard-endpoint-private-marker/v1'
KEY_ENV = 'YLC_DASHBOARD_UNUSED_KEY'
SAFE_COMMAND = 'dir /b' if os.name == 'nt' else 'pwd'
PRIVATE_TASK = '只属于主任务的内容；保留 "带空格的文字" 和 C:\\中文目录\\space name\\file.txt。'
MISSING = object()


def usage(inputs, outputs, cache=MISSING):
    value = {'prompt_tokens': inputs, 'completion_tokens': outputs}
    if cache is not MISSING:
        value['prompt_tokens_details'] = {'cached_tokens': cache}
    return value


def answer(text, counts=MISSING):
    response = {'choices': [{'message': {'role': 'assistant', 'content': text}, 'finish_reason': 'stop'}]}
    if counts is not MISSING:
        response['usage'] = counts
    return response


def tool(name, arguments, counts, identifier):
    response = answer('', counts)
    response['choices'][0] = {'message': {'role': 'assistant', 'content': '', 'tool_calls': [
        {'id': identifier, 'type': 'function', 'function': {
            'name': name, 'arguments': json.dumps(arguments, ensure_ascii=False)}}]}, 'finish_reason': 'tool_calls'}
    return response


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __enter__(self):
        super().__enter__()
        self.serving = threading.Thread(target=self.serve_forever, daemon=True)
        self.serving.start()
        return self

    def __exit__(self, *args):
        self.shutdown()
        self.serving.join(5)
        return super().__exit__(*args)


class FixtureHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == ENDPOINT_PATH + '/chat/completions', self.path
            assert 'Authorization' not in self.headers
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            self.server.requests.append(request)
            assert request['model'] == MODEL
            assert request.get('stream') is not True
            index = len(self.server.requests) - 1
            assert index < len(self.server.responses), '出现未预期的额外模型请求'
            response = self.server.responses[index]
            if request.get('tools'):
                tasks = [message['content'] for message in request['messages'] if message['role'] == 'user']
                assert tasks and tasks == self.server.tasks[:len(tasks)], ('中文主任务或参数引用发生变化', tasks)
                self.server.observed_tasks.update(tasks)
            if self.server.case == 'review-included' and index == 1:
                assert not request.get('tools'), '审核子 agent 不应获得执行工具'
                assert 1 <= request['max_tokens'] <= 96
                assert '只属于主任务的内容' not in json.dumps(request, ensure_ascii=False)
                assert SAFE_COMMAND in json.dumps(request, ensure_ascii=False)
            elif index and response['choices'][0]['message']['content']:
                assert request.get('tools'), '主智能体请求应包含工具定义'
                if self.server.case == 'review-included':
                    results = [message['content'] for message in request['messages'] if message['role'] == 'tool']
                    assert len(results) == 1 and '退出码：0' in results[0], results
                elif self.server.case in ('interactive', 'weighted-cache', 'partial-cache'):
                    assert any(message['role'] == 'tool' and 'dashboard marker' in message['content']
                               for message in request['messages']), '文件工具结果没有回传给模型'
            content = json.dumps(response, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as failure:
            self.server.errors.append(repr(failure))
            try:
                self.send_response(500)
                self.send_header('Content-Length', '0')
                self.end_headers()
            except OSError:
                pass


def assert_rate(actual, expected):
    if expected is None:
        assert actual is None, ('缓存率应为未报告', actual)
    else:
        assert isinstance(actual, (int, float)) and math.isclose(actual, expected, rel_tol=1e-9), (actual, expected)


def assert_compact_status(text, context):
    # 紧贴输入提示符的末尾五行必须自足，不能借用欢迎标题或更早的 /status 明细。
    lines = [line.strip() for line in text.splitlines() if line.strip()][-5:]
    footer = '\n'.join(lines)
    for label in ('模型', MODEL, '厂商', '127.0.0.1', '输入 token', '输出 token',
                  '缓存 token', '缓存命中率'):
        assert label in footer, ('紧凑底部状态缺少字段', context, label, footer)
    assert '本轮统计' not in footer and '进程累计' not in footer, (context, footer)
    return footer


def assert_report(result, requests, inputs, outputs, cached, input_reports, output_reports,
                  cache_reports, rate, reported_rate):
    assert result['status'] == 'COMPLETED', result
    assert result['provider'] == PROVIDER and result['model'] == MODEL, result
    assert result['providerHost'] == '127.0.0.1', result['providerHost']
    expected_usage = {'inputTokens': inputs, 'outputTokens': outputs, 'cachedInputTokens': cached}
    assert result['usage'] == expected_usage, (result['usage'], expected_usage)
    report = result['usageReport']
    assert report['scope'] == 'current-task'
    assert report['requests'] == requests, report
    assert report['total'] == expected_usage, report
    assert report['inputReportedRequests'] == input_reports, report
    assert report['outputReportedRequests'] == output_reports, report
    assert report['cacheReportedRequests'] == cache_reports, report
    assert_rate(report['cacheHitRatePercent'], rate)
    assert_rate(report['reportedCacheHitRatePercent'], reported_rate)
    return report


def execute(launcher, workspace, case, responses, arguments, input_text=''):
    # 显式配置和未设置的凭据变量隔离用户配置；夹具不会读取用户的密钥或历史会话。
    config = workspace / 'offline.yaml'
    config.write_text('{}\n', encoding='utf-8')
    (workspace / 'marker.txt').write_text('dashboard marker\n', encoding='utf-8')
    environment = os.environ.copy()
    environment.pop(KEY_ENV, None)
    with FixtureServer(('127.0.0.1', 0), FixtureHandler) as server:
        server.requests = []
        server.errors = []
        server.responses = responses
        server.case = case
        server.tasks = arguments[arguments.index('run') + 1:] if 'run' in arguments else [
            value for value in input_text.splitlines() if value and not value.startswith('/')]
        server.observed_tasks = set()
        launch = [str(Path(launcher).resolve())] if launcher else [
            'java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
            '-Dstderr.encoding=UTF-8', '-jar', str(JAR)]
        common = ['--config', str(config), '--provider', PROVIDER, '--model', MODEL,
            '--base-url', f'http://127.0.0.1:{server.server_port}' + ENDPOINT_PATH,
            '--api-key-env', KEY_ENV, '--no-stream', '--no-color']
        cli_arguments = common + arguments
        if os.name == 'nt':
            # Java 21 的 ACP 参数转换发生在 main 之前；让 Picocli 按 UTF-8 读原始参数。
            argument_file = workspace / 'cli-arguments.txt'
            argument_file.write_text('\n'.join(json.dumps(value, ensure_ascii=False) for value in cli_arguments) + '\n', encoding='utf-8')
            cli_arguments = ['@' + argument_file.name]
        completed = subprocess.run(launch + cli_arguments, input=input_text, capture_output=True,
            text=True, encoding='utf-8', env=environment, cwd=workspace, timeout=35)
    assert completed.returncode == 0, (case, completed.stdout, completed.stderr, server.errors)
    assert not server.errors, (case, server.errors)
    assert len(server.requests) == len(responses), (case, len(server.requests), len(responses))
    assert server.observed_tasks == set(server.tasks), (case, server.observed_tasks, server.tasks)
    sessions = list((workspace / '.harness/sessions').glob('*.json'))
    assert sessions, '验收必须在实际中文工作区保存会话'
    for path in sessions:
        session = json.loads(path.read_text(encoding='utf-8'))
        assert Path(session['workspace']).samefile(workspace), '会话工作区不能重定向或丢失中文'
        assert [message['content'] for message in session['transcript'] if message['role'] == 'USER'] == server.tasks
    combined = completed.stdout + completed.stderr
    assert '\x1b[' not in combined, '纯文本模式出现 ANSI 控制序列'
    assert 'dashboard-endpoint-private-marker' not in combined, '端点标签泄漏了 URL 路径'
    assert '[y/N]' not in combined, '管道输入不应显示交互审批提示'
    return completed, len(server.requests)


def verify_dashboard(launcher=None):
    records = []
    preview = []
    with tempfile.TemporaryDirectory(prefix='ylc dashboard 中文 ') as temporary:
        base = Path(temporary)

        def run(case, responses, arguments=None, input_text=''):
            workspace = base / ('工作区 ' + case)
            workspace.mkdir()
            completed, requests = execute(launcher, workspace, case, responses,
                arguments if arguments is not None else ['--quiet', '--json', 'run', PRIVATE_TASK], input_text)
            records.append({'case': case, 'localhostRequests': requests, 'status': 'PASS'})
            return completed

        read_args = {'path': 'marker.txt'}
        interactive = run('interactive', [
            tool('read_file', read_args, usage(20, 2, 10), 'dashboard-first-read'),
            answer('第一轮任务完成。', usage(100, 4, 90)),
            answer('第二轮任务完成。', usage(80, 6, 0))], [], '请读取 marker.txt。\n再回复一句话。\n/status\n/exit\n')
        stdout, stderr = interactive.stdout, interactive.stderr
        assert stdout.count('第一轮任务完成。') == 1 and stdout.count('第二轮任务完成。') == 1
        assert '第一轮任务完成。' not in stderr and '第二轮任务完成。' not in stderr
        assert '正在思考' not in stdout and '读取文件' not in stdout
        assert '模型' in stdout and MODEL in stdout and '厂商' in stdout and PROVIDER in stdout
        assert '127.0.0.1' in stdout
        assert '本轮统计' not in stdout + stderr, '普通交互不应重复展开整块本轮统计'
        assert stdout.count('── 进程累计 ──') == 1, '累计详情只应由显式 /status 展开一次'
        for line in stdout.splitlines():
            if 'ylc> ' in line or line.strip() == 'ylc>':
                assert line.strip() == 'ylc>', ('提示符和后续内容挤在一起', line)
        assert stdout.count('ylc>') == 4
        # 管道模式不重绘屏幕，但每次输入前均有紧凑状态区，不能把状态塞进回答正文。
        before_prompt = stdout.split('ylc>')[:-1]
        for index, region in enumerate(before_prompt):
            footer = assert_compact_status(region, '交互提示符 ' + str(index + 1))
            if index == 0:
                assert '尚无请求' in footer, ('初始底栏不得伪造零用量', footer)
        for label in ('进程累计', '输入 token', '输出 token', '缓存命中率', '缓存 token'):
            assert label in stdout + stderr, (label, stdout, stderr)
        # /status 属于 stdout；累计命中率必须按请求 token 加权，不能平均每轮百分比。
        assert re.search(r'缓存命中率[^\n]*50(?:\.0+)?\s*%', stdout), stdout
        assert re.search(r'输入 token[^\n]*200\b', stdout), stdout
        assert re.search(r'输出 token[^\n]*12\b', stdout), stdout
        assert '\n\n' in stdout and '\n\n' in stderr, '任务、回答与状态应留出段间空白'
        preview += ['交互 stdout（每次输入前显示紧凑状态）：', stdout, '交互 stderr（仅进度）：', stderr]

        # 20 输入/10 缓存和 180 输入/180 缓存的合计为 95%，不是单次百分比的均值 75%。
        weighted = run('weighted-cache', [tool('read_file', read_args, usage(20, 2, 10), 'weighted-read'),
            answer('加权缓存验收。', usage(180, 8, 180))])
        weighted_result = json.loads(weighted.stdout)
        assert_report(weighted_result, 2, 200, 10, 190, 2, 2, 2, 95, 95)

        zero = run('cache-zero', [answer('显式零缓存。', usage(50, 5, 0))])
        assert_report(json.loads(zero.stdout), 1, 50, 5, 0, 1, 1, 1, 0, 0)

        missing = run('cache-missing', [answer('厂商未提供缓存字段。', usage(50, 5))])
        assert_report(json.loads(missing.stdout), 1, 50, 5, 0, 1, 1, 0, None, None)

        unknown = run('usage-missing', [answer('厂商未提供用量。')])
        assert_report(json.loads(unknown.stdout), 1, 0, 0, 0, 0, 0, 0, None, None)

        partial = run('partial-cache', [tool('read_file', read_args, usage(20, 2, 10), 'partial-read'),
            answer('部分请求缓存数据缺失。', usage(180, 8))])
        assert_report(json.loads(partial.stdout), 2, 200, 10, 10, 2, 2, 1, None, 50)

        invalid = run('cache-exceeds-input', [answer('缓存字段不合理。', usage(10, 2, 20))])
        assert_report(json.loads(invalid.stdout), 1, 10, 2, 20, 1, 1, 1, None, None)

        reviewed = run('review-included', [
            tool('run_command', {'command': SAFE_COMMAND}, usage(100, 10, 60), 'dashboard-approved-command'),
            answer('{"decision":"approve"}', usage(40, 4, 20)),
            answer('审核用量已计入。', usage(80, 8, 40))])
        reviewed_result = json.loads(reviewed.stdout)
        assert_report(reviewed_result, 3, 220, 22, 120, 3, 3, 3, 120 / 220 * 100, 120 / 220 * 100)
        assert reviewed_result['iterations'] == 2
        assert reviewed_result['review']['requests'] == 1
        assert reviewed_result['review']['approved'] == 1
        assert reviewed_result['agentUsage']['inputTokens'] == 180

        plain = run('plain-quiet', [answer('这行是唯一的标准输出。', usage(40, 4, 0))],
            ['--quiet', 'run', '回复一句话'])
        assert plain.stdout == '这行是唯一的标准输出。\n', plain.stdout
        for marker in ('正在思考', '整理结果', '执行进度'):
            assert marker not in plain.stderr, plain.stderr
        assert '本轮统计' not in plain.stderr, '单次运行统计应使用紧凑状态区'
        assert_compact_status(plain.stderr, '--quiet run stderr')
        for label in ('模型', '厂商', MODEL, '127.0.0.1', '输入 token', '输出 token', '缓存命中率'):
            assert label in plain.stderr, (label, plain.stderr)
        assert re.search(r'缓存命中率[^\n]*0(?:\.0+)?\s*%', plain.stderr), plain.stderr
        preview += ['单次 run stdout：', plain.stdout, '单次 run stderr（--quiet 保留统计）：', plain.stderr]

    # 公开 localhost 夹具输出，便于检查布局；不包含真实模型、凭据或用户会话。
    preview_file = ROOT / 'target/dashboard-preview.txt'
    preview_file.parent.mkdir(parents=True, exist_ok=True)
    preview_file.write_text('\n'.join(preview), encoding='utf-8')
    return {'status': 'PASS', 'localhostRequests': sum(record['localhostRequests'] for record in records),
        'cases': records, 'preview': str(preview_file),
        'scope': '仅 localhost 真实 CLI；核验加权缓存率、缺失/零用量、审核合计和输出通道，不测量线上模型质量或延迟。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选：安装版或便携版启动器。')
    parser.add_argument('--result', type=Path, help='将可复现验收结果写入 JSON。')
    options = parser.parse_args()
    result = verify_dashboard(options.launcher)
    if options.result:
        options.result.parent.mkdir(parents=True, exist_ok=True)
        options.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
