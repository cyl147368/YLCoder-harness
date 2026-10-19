"""使用 localhost 验证真实 CLI 的审核子 agent、用量、缓存和失败回退。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
PRIVATE_TASK = '只属于主智能体的任务内容，不应发送给审核子agent；保留 "带空格的文字" 和 C:\\中文目录\\space name\\file.txt。'
SAFE_COMMAND = 'dir /b' if os.name == 'nt' else 'pwd'


def verify_review(launcher=None):
    records = []
    with tempfile.TemporaryDirectory(prefix='ylc review 中文 ') as temporary:
        base = Path(temporary)
        for case in ('approved-cache', 'manual', 'malformed', 'http-error', 'disabled', 'high-risk', 'all-allowed',
                     'git-status-approved', 'git-status-manual', 'git-status-filter'):
            workspace = base / case
            workspace.mkdir()
            marker = workspace / 'marker.txt'
            marker.write_text('fixture\n', encoding='utf-8')
            nested = workspace / 'nested'
            nested.mkdir()
            (nested / 'SHOULD_NOT_LIST_RECURSIVELY.txt').write_text('fixture', encoding='utf-8')
            config = workspace / 'offline.yaml'
            config.write_text('{}\n', encoding='utf-8')
            state = {'main': [], 'review': [], 'errors': []}
            command = 'echo AUTO_REVIEW_BLOCKED > blocked.txt' if case == 'high-risk' else SAFE_COMMAND
            repeated = case in ('approved-cache', 'git-status-approved')
            if case.startswith('git-status-'):
                command = 'git status --short'
                git = shutil.which('git')
                assert git, 'Git 状态审核验收需要已安装的 Git'
                git_env = {k: v for k, v in os.environ.items() if not k.upper().startswith('GIT_')}
                git_env.update({'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_SYSTEM': os.devnull})
                subprocess.run([git, '-c', 'init.templateDir=', 'init', '--quiet'], cwd=workspace, env=git_env, check=True, capture_output=True)
                subprocess.run([git, 'config', 'core.fsmonitor', 'echo blocked > blocked.txt'],
                               cwd=workspace, env=git_env, check=True, capture_output=True)
                if case == 'git-status-filter':
                    subprocess.run([git, 'config', 'filter.fixture.clean', 'echo blocked > blocked.txt'],
                                   cwd=workspace, env=git_env, check=True, capture_output=True)

            class Provider(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_POST(self):
                    try:
                        assert self.path == '/v1/chat/completions'
                        assert 'Authorization' not in self.headers
                        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                        review = not body.get('tools')
                        state['review' if review else 'main'].append(body)
                        if not review:
                            assert [m['content'] for m in body['messages'] if m['role'] == 'user'] == [PRIVATE_TASK], '中文主任务或参数引用发生变化'
                        if review:
                            assert body.get('stream') is not True
                            assert 1 <= body['max_tokens'] <= 96
                            assert len(body['messages']) == 2
                            assert PRIVATE_TASK not in json.dumps(body, ensure_ascii=False)
                            assert command in json.dumps(body, ensure_ascii=False)
                            assert 'fixture\n' not in json.dumps(body, ensure_ascii=False)
                            if case == 'http-error':
                                self.send_response(503)
                                self.end_headers()
                                self.wfile.write(b'DO_NOT_LOG_REVIEW_SECRET')
                                return
                            text = '{"decision":"manual"}' if case in ('manual', 'git-status-manual') else (
                                '{"decision":"approve"} trailing' if case == 'malformed' else '{"decision":"approve"}')
                            message = {'role': 'assistant', 'content': text}
                        elif len(state['main']) == 1:
                            message = {'role': 'assistant', 'content': '', 'tool_calls': [
                                {'id': f'auto-review-{index}', 'type': 'function', 'function': {
                                    'name': 'run_command', 'arguments': json.dumps({'command': command})}}
                                for index in range(2 if repeated else 1)]}
                        else:
                            assert len(state['main']) == 2
                            outputs = [m['content'] for m in body['messages'] if m['role'] == 'tool']
                            assert len(outputs) == (2 if repeated else 1)
                            approved = case in ('approved-cache', 'all-allowed', 'git-status-approved')
                            assert all(('退出码：0' if approved else '未获批准') in output for output in outputs), outputs
                            if case == 'approved-cache' and os.name == 'nt':
                                assert all('SHOULD_NOT_LIST_RECURSIVELY' not in output for output in outputs)
                            message = {'role': 'assistant', 'content': '本地审核验收完成。'}
                        response = json.dumps({'choices': [{'message': message,
                            'finish_reason': 'tool_calls' if message.get('tool_calls') else 'stop'}],
                            'usage': {'prompt_tokens': 50 if review else 13,
                                      'completion_tokens': 7 if review else 3}}).encode('utf-8')
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(response)))
                        self.end_headers()
                        self.wfile.write(response)
                    except Exception as failure:
                        state['errors'].append(repr(failure))
                        self.send_response(500)
                        self.end_headers()

            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                environment = os.environ.copy()
                environment.pop('HARNESS_AUTO_REVIEW_UNUSED', None)
                environment['DIRCMD'] = '/s'
                launch = [str(Path(launcher).resolve())] if launcher else [
                    'java', '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                    '-Dstderr.encoding=UTF-8', '-jar', str(JAR)]
                arguments = ['--config', str(config), '--workspace', str(workspace),
                    '--provider', 'openai-compatible', '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                    '--model', 'auto-review-fixture', '--api-key-env', 'HARNESS_AUTO_REVIEW_UNUSED',
                    '--no-stream', '--quiet', '--json']
                if case == 'disabled':
                    arguments.append('--no-auto-review')
                if case == 'all-allowed':
                    arguments += ['--allow-commands', '--no-auto-review']
                arguments += ['run', PRIVATE_TASK]
                if os.name == 'nt':
                    # Java 21 的原生 Windows 参数先经过 ACP；应用级参数文件保留中文。
                    # 文件名保持相对 ASCII，内容仍覆盖真实中文路径、空格、引号和反斜线。
                    argument_file = workspace / 'cli-arguments.txt'
                    argument_file.write_text('\n'.join(json.dumps(value, ensure_ascii=False) for value in arguments) + '\n', encoding='utf-8')
                    arguments = ['@' + argument_file.name]
                try:
                    run = subprocess.run(launch + arguments, input='', capture_output=True,
                        text=True, encoding='utf-8', env=environment, cwd=workspace, timeout=35)
                finally:
                    server.shutdown()
                    thread.join(5)
            assert run.returncode == 0, (case, run.stdout, run.stderr, state['errors'])
            assert not state['errors'], (case, state['errors'])
            result = json.loads(run.stdout)
            assert result['status'] == 'COMPLETED' and result['iterations'] == 2, (case, result)
            assert 'DO_NOT_LOG_REVIEW_SECRET' not in run.stdout + run.stderr
            assert '[y/N]' not in run.stderr, '管道模式不能读取确认回答'
            assert not (workspace / 'blocked.txt').exists()
            assert marker.read_text(encoding='utf-8') == 'fixture\n'
            expected_reviews = int(case in ('approved-cache', 'manual', 'malformed', 'http-error', 'git-status-approved', 'git-status-manual'))
            assert len(state['main']) == 2 and len(state['review']) == expected_reviews, (case, state)
            review = result['review']
            assert review['requests'] == expected_reviews
            assert review['cacheHits'] == int(repeated)
            assert review['approved'] == (2 if repeated else 0)
            assert review['unreportedRequests'] == int(case == 'http-error')
            known = expected_reviews and case != 'http-error'
            assert result['usage']['inputTokens'] == 26 + (50 if known else 0), (case, result)
            assert result['usage']['outputTokens'] == 6 + (7 if known else 0)
            assert result['agentUsage']['inputTokens'] == 26 and result['agentUsage']['outputTokens'] == 6
            session = json.loads((workspace / '.harness/sessions' / (result['session'] + '.json')).read_text(encoding='utf-8'))
            assert Path(session['workspace']).samefile(workspace), '会话必须使用实际中文工作区'
            assert [m['content'] for m in session['transcript'] if m['role'] == 'USER'] == [PRIVATE_TASK]
            assert all(m.get('content') != '{"decision":"approve"}' for m in session['transcript'])
            records.append({'case': case, 'mainRequests': len(state['main']), 'reviewRequests': expected_reviews,
                            'review': review, 'status': 'PASS'})
    return {'status': 'PASS', 'localhostRequests': sum(x['mainRequests'] + x['reviewRequests'] for x in records),
            'cases': records, 'scope': '仅本地模拟厂商；不请求真实模型或测量线上质量/延迟。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path)
    parser.add_argument('--result', type=Path)
    options = parser.parse_args()
    result = verify_review(options.launcher)
    if options.result:
        options.result.parent.mkdir(parents=True, exist_ok=True)
        options.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
