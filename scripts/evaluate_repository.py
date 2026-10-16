"""真实微型仓库修复，使用独立编译与断言验证。

外部调用需要 --live，并显式设置 HTTPS 地址、模型和凭据环境变量。
--offline 使用提供已知修复的 localhost 夹具，不作为人工智能质量证据。
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from evaluate_coding import redact_report
from repository_fixtures import TASKS
from check_repository_fixtures import verify_workspace

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
SCRIPT_HASH = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
FIXTURE_HASH = hashlib.sha256(json.dumps(TASKS, sort_keys=True).encode()).hexdigest()
CHECKER_HASH = hashlib.sha256((ROOT / 'scripts/check_repository_fixtures.py').read_bytes()).hexdigest()


def source_snapshot(workspace, task):
    result = {}
    for name in task['sources']:
        path = workspace / name
        if path.is_symlink() or path.resolve() != workspace.resolve() / Path(name):
            raise ValueError('源码路径无效')
        data = path.read_bytes()
        if len(data) > 1_000_000:
            raise ValueError('源码过大')
        result[name] = data
    return result


def verify(workspace, task, java, javac):
    return verify_workspace(task, workspace, java, javac, timeout=30)


def allowed_workspace(workspace, task):
    allowed = set(task['sources'])
    for path in workspace.rglob('*'):
        relative = path.relative_to(workspace)
        if path.is_symlink() or path.resolve() != workspace.resolve() / relative:
            return False
        if path.is_file() and path.suffix.lower() == '.java' and relative.as_posix() not in allowed:
            return False
        if relative.parts[0] == '.harness':
            if not (path.is_file() or path.is_dir()):
                return False
        elif path.is_file() and relative.as_posix() not in allowed:
            return False
        elif path.is_dir() and not any(name.startswith(relative.as_posix() + '/') for name in allowed):
            return False
    return True


def valid_https_endpoint(value):
    try:
        endpoint = urlsplit(value or '')
        return (endpoint.scheme == 'https' and bool(endpoint.hostname) and endpoint.username is None
                and endpoint.password is None and not endpoint.query and not endpoint.fragment
                and (endpoint.port is None or 0 < endpoint.port < 65536)
                and not any(c.isspace() or ord(c) < 32 for c in value)
                and not any(c in value for c in ('?', '#', '@', '\\')))
    except ValueError:
        return False


class OfflinePolicy(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        server = self.server
        try:
            self.connection.settimeout(10)
            assert self.path == '/v1/chat/completions'
            assert self.headers.get('Authorization') is None
            length = int(self.headers['Content-Length'])
            assert 0 < length < 2_000_000
            body = json.loads(self.rfile.read(length))
            assert body['stream'] is True
            assert body['max_tokens'] == 1800
            server.requests += 1
            assert server.requests <= 2
            if server.requests == 1:
                calls = [{'id': f'repair-{index}', 'type': 'function', 'function': {
                    'name': 'edit_file', 'arguments': json.dumps(edit)}}
                    for index, edit in enumerate(server.task['reference_edits'])]
                message, finish = {'role': 'assistant', 'content': '', 'tool_calls': calls}, 'tool_calls'
            else:
                results = [m for m in body['messages'] if m['role'] == 'tool']
                assert len(results) == len(server.task['reference_edits'])
                assert [m['tool_call_id'] for m in results] == [f'repair-{n}' for n in range(len(results))]
                assert all(m['content'].startswith(('Edited ', '已编辑 ')) for m in results)
                message, finish = {'role': 'assistant', 'content': '已应用夹具修复，随后执行独立检查。'}, 'stop'
            payload = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:
            server.failures.append(type(error).__name__)
            self.send_response(422)
            self.send_header('Content-Length', '0')
            self.end_headers()


def run_case(args, task, variant, repeat):
    budget = args.baseline_budget if variant == 'baseline' else args.compact_budget
    with tempfile.TemporaryDirectory(prefix='harness-repository-') as temporary:
        parent = Path(temporary)
        workspace = parent / 'workspace'
        workspace.mkdir()
        for name, source in task['sources'].items():
            target = workspace / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.encode('utf-8'))
        initial = source_snapshot(workspace, task)
        server = None
        if args.offline:
            server = ThreadingHTTPServer(('127.0.0.1', 0), OfflinePolicy)
            server.task, server.requests, server.failures = task, 0, []
            threading.Thread(target=server.serve_forever, daemon=True).start()
        endpoint = f'http://127.0.0.1:{server.server_port}/v1' if server else args.base_url
        variable = 'HARNESS_REPOSITORY_UNUSED' if args.offline else args.credential_env
        model = 'repository-local-fixture' if args.offline else args.model
        config = parent / 'trusted.yaml'
        config.write_text('provider: openai-compatible\nmodel: ' + json.dumps(model) + '\nbase-url: ' + json.dumps(endpoint)
            + '\napi-key-env: ' + json.dumps(variable) + '\ntimeout-seconds: 60\nagent:\n  max-output-tokens: 1800\n'
            + 'permissions:\n  allow-commands: false\n  allow-mcp-tools: false\n', encoding='utf-8')
        prompt = task['prompt'] + (' 保留公开 API，仅修改现有生产源码文件。'
            '无关辅助文件的字节保持不变。按需检查相关文件，优先使用 edit_file 做集中修改，不执行 shell 命令。'
            '独立编译与断言在退出后执行；最后简要说明修改内容。')
        command = [args.java, '-jar', str(JAR), '--config', str(config), '--workspace', str(workspace),
            '--context-budget', str(budget), '--parallel-reads', '4', '--max-iterations', str(args.max_iterations),
            '--quiet', '--json', 'run', prompt]
        environment = dict(os.environ)
        if args.offline:
            environment.pop(variable, None)
        started = time.perf_counter()
        try:
            completed = subprocess.run(command, env=environment, capture_output=True, timeout=args.timeout)
            elapsed = (time.perf_counter() - started) * 1000
            try:
                result = json.loads(completed.stdout)
                if not isinstance(result, dict):
                    result = {}
            except ValueError:
                result = {}
            quality = verify(workspace, task, args.java, args.javac)
            final = source_snapshot(workspace, task)
            changed = [name for name in initial if final[name] != initial[name]]
            required = set(changed) == set(task['expected_changed_paths'])
            unrelated = set(task['sources']) - set(task['expected_changed_paths'])
            unchanged = all(final[name] == initial[name] for name in unrelated)
            allowlist = allowed_workspace(workspace, task)
            failures = server.failures if server else []
            passed = completed.returncode == 0 and result.get('status') == 'COMPLETED' and quality['passed'] and required and unchanged and allowlist and not failures
            return {'task': task['id'], 'variant': variant, 'repeat': repeat, 'passed': passed,
                'status': result.get('status', 'NO_RESULT'), 'exitCode': completed.returncode,
                'quality': quality, 'requiredFilesChanged': required, 'exactChangedPaths': required,
                'unrelatedSourcesUnchanged': unchanged,
                'workspaceAllowlist': allowlist, 'changedPaths': changed, 'iterations': result.get('iterations'),
                'agentError': result.get('error'), 'processElapsedMs': elapsed, 'agentElapsedMs': result.get('elapsedMs'),
                'contextEstimateBudget': budget, 'providerUsage': result.get('usage') if args.live else None,
                'usageAvailability': 'adapter-reported; zero may mean missing; billing not verified' if args.live else '无数据：localhost 夹具',
                'offlineRequests': server.requests if server else None, 'offlineFailures': failures,
                'sourceSha256': {name: hashlib.sha256(data).hexdigest() for name, data in final.items()}}
        except subprocess.TimeoutExpired:
            return {'task': task['id'], 'variant': variant, 'repeat': repeat, 'passed': False, 'status': 'TIMEOUT'}
        except (OSError, ValueError):
            return {'task': task['id'], 'variant': variant, 'repeat': repeat, 'passed': False, 'status': 'INVALID_SOURCE_OR_RESULT'}
        finally:
            if server:
                server.shutdown()
                server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--live', action='store_true')
    mode.add_argument('--offline', action='store_true')
    parser.add_argument('--base-url')
    parser.add_argument('--model')
    parser.add_argument('--credential-env')
    parser.add_argument('--task', choices=[task['id'] for task in TASKS])
    parser.add_argument('--variants', choices=['baseline', 'compact', 'paired'], default='compact')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--baseline-budget', type=int, default=48000)
    parser.add_argument('--compact-budget', type=int, default=12000)
    parser.add_argument('--max-iterations', type=int, default=16)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    if not JAR.is_file() or not 1 <= args.repeat <= 3 or not 2 <= args.max_iterations <= 24 or args.timeout <= 0:
        parser.error('要求已构建 jar，repeat 为 1..3，max-iterations 为 2..24，timeout 为正数')
    if not 0 < args.compact_budget < args.baseline_budget:
        parser.error('要求 0 < compact-budget < baseline-budget')
    if args.live and (not valid_https_endpoint(args.base_url)
                     or not args.model or not args.credential_env or not os.getenv(args.credential_env)):
        parser.error('--live 需要显式 HTTPS 基础地址、模型和已配置的凭据变量')
    tasks = [task for task in TASKS if not args.task or args.task == task['id']]
    rows = []
    for repeat in range(1, args.repeat + 1):
        order = ['baseline', 'compact'] if repeat % 2 else ['compact', 'baseline']
        if args.variants != 'paired':
            order = [args.variants]
        for task in tasks:
            for variant in order:
                row = run_case(args, task, variant, repeat)
                row['executionOrdinal'], row['pairOrder'] = len(rows) + 1, order
                rows.append(row)
    report = {'evaluationVersion': 1, 'mode': 'live-miniature-repository' if args.live else 'localhost-known-repair',
        'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': 'Related-file Java repairs on generated miniature repositories; actual live model patches are independently compiled/asserted. Offline policy supplies known patches. No broad repository/billing/speed claim.',
        'artifactSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'evaluatorSha256': SCRIPT_HASH,
        'fixtureSha256': FIXTURE_HASH, 'checkerSha256': CHECKER_HASH,
        'provider': 'openai-compatible', 'model': args.model if args.live else 'repository-local-fixture',
        'baseUrl': args.base_url if args.live else 'loopback fixture', 'outputLimit': 1800, 'maxIterations': args.max_iterations,
        'taskMetadata': [{'id': task['id'], 'sourceFiles': len(task['sources']),
            'sourceLines': sum(len(source.splitlines()) for source in task['sources'].values()),
            'requiredChangedPaths': task['expected_changed_paths'], 'unchangedPaths': task['unchanged_paths'], 'prompt': task['prompt']} for task in tasks],
        'passed': all(row['passed'] for row in rows), 'runs': rows,
        'summary': {variant: {'runs': len([r for r in rows if r['variant'] == variant]),
            'passed': sum(r['passed'] for r in rows if r['variant'] == variant),
            'medianProcessElapsedMs': statistics.median(r.get('processElapsedMs', args.timeout*1000) for r in rows if r['variant'] == variant)}
            for variant in ('baseline', 'compact') if any(r['variant'] == variant for r in rows)}}
    print(json.dumps(redact_report(report, os.getenv(args.credential_env, '') if args.live else ''), indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
