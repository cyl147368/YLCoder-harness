"""使用打包 Java harness 进行离线成对多文件工作流评估。

localhost 策略提供已知修改，用于测量工具、上下文和恢复行为；
不代表人工智能推理、厂商计费或生产生成延迟。
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shlex
import statistics
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from multifile_fixtures import TASKS

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'


def prepare(workspace, task):
    for name, source in task['sources'].items():
        path = workspace / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')


def apply_edits(workspace, edits):
    for edit in edits:
        path = workspace / edit['path']
        source = path.read_text(encoding='utf-8')
        if source.count(edit['old_text']) != 1:
            raise ValueError('夹具修改必须恰好匹配一次：' + edit['path'])
        path.write_text(source.replace(edit['old_text'], edit['new_text']), encoding='utf-8')


def verify(workspace, task, args):
    # 可信断言仅在代理停止后创建，并编译到新的独立目录；
    # 代理之前生成的旧 .class 文件不能满足这些断言。
    with tempfile.TemporaryDirectory(prefix='harness-independent-check-') as temporary:
        check_root = Path(temporary)
        check = check_root / 'Check.java'
        check.write_text(task['checks'], encoding='utf-8')
        sources = sorted((workspace / 'src').rglob('*.java'))
        try:
            compiled = subprocess.run([args.javac, '-encoding', 'UTF-8', '-proc:none',
                                       '-classpath', str(check_root), '-sourcepath', str(check_root), '-d', str(check_root),
                                       *map(str, sources), str(check)], cwd=check_root, capture_output=True, timeout=20)
            if compiled.returncode:
                return {'passed': False, 'phase': 'compile', 'exitCode': compiled.returncode}
            checked = subprocess.run([args.java, '-cp', str(check_root), 'Check'],
                                     cwd=check_root, capture_output=True, timeout=20)
            return {'passed': checked.returncode == 0 and b'CHECKS_PASS' in checked.stdout,
                    'phase': 'assertions', 'exitCode': checked.returncode}
        except subprocess.TimeoutExpired:
            return {'passed': False, 'phase': 'timeout'}


def checker_controls(args):
    controls = []
    for task in TASKS:
        variants = [('buggy', [], False), ('repaired', task['edits'], True)]
        variants += [('omitted-edit-' + str(index + 1),
                      task['edits'][:index] + task['edits'][index + 1:], False)
                     for index in range(len(task['edits']))]
        results = []
        for label, edits, expected in variants:
            with tempfile.TemporaryDirectory(prefix='harness-check-control-') as temporary:
                workspace = Path(temporary)
                prepare(workspace, task)
                apply_edits(workspace, edits)
                result = verify(workspace, task, args)
                results.append({'variant': label, 'expectedPass': expected, **result})
                if result['passed'] != expected:
                    raise AssertionError('独立验证器对照失败：' + task['id'] + '/' + label)
        controls.append({'task': task['id'], 'allControlsPassed': True, 'variants': results})
    return controls


def shell_command(parts):
    return subprocess.list2cmdline(parts) if os.name == 'nt' else shlex.join(parts)


def trajectory(task, args):
    steps = [('history-' + str(index), [('read_file', {'path': f'logs/history-{index}.txt'})])
             for index in range(1, 13)]
    steps += [('discover', [('list_files', {'path': 'src'})]),
              ('sources', [('read_file', {'path': name}) for name in task['sources']]),
              ('stale-edit', [('edit_file', {**task['edits'][0], 'expected_sha256': '0' * 64})]),
              ('refresh', [('read_file', {'path': task['edits'][0]['path']})]),
              ('repair', [('edit_file', edit) for edit in task['edits']]),
              ('compile', [('run_command', {'command': shell_command([
                  args.javac, '-encoding', 'UTF-8', '-d', 'compiled', *task['sources']])})]),
              ('changes', [('changes', {})])]
    return steps


class Policy(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        server = self.server
        index = len(server.payloads)
        server.payloads.append(body)
        try:
            messages = body['messages']
            if not any(m['role'] == 'user' and server.task['prompt'] in m.get('content', '') for m in messages):
                raise AssertionError('原始用户要求丢失')
            if index:
                label, calls = server.steps[index - 1]
                expected_ids = [f'{index - 1}-{n}' for n in range(len(calls))]
                results = [m for m in messages if m['role'] == 'tool' and m.get('tool_call_id') in expected_ids]
                if [m['tool_call_id'] for m in results] != expected_ids:
                    raise AssertionError('最近的工具结果缺失或顺序错误：' + label)
                text = '\n'.join(m.get('content', '') for m in results)
                if label.startswith('history-'):
                    original = (server.workspace / calls[0][1]['path']).read_text(encoding='utf-8')
                    expected = ''.join(f'{n}: {line}\n' for n, line in enumerate(original.splitlines(), 1))
                    if text != expected:
                        raise AssertionError('历史读取未提供预期负载')
                    server.history_validated += 1
                elif label == 'discover':
                    if any(name not in text for name in server.task['sources']):
                        raise AssertionError('发现结果遗漏源文件')
                    server.discovery_validated = True
                elif label == 'stale-edit':
                    if not any(marker in text for marker in ('expected_sha256 does not match', '文件已变化（expected_sha256 与当前内容不匹配）')):
                        raise AssertionError('过期修改未按预期失败')
                    if any((server.workspace / name).read_bytes() != source
                           for name, source in server.initial_sources.items()):
                        raise AssertionError('被拒绝的过期修改改变了源码')
                    server.stale_rejected = True
                elif label == 'repair' and any(not any(marker in m['content'] for marker in ('replaced 1 literal occurrence', '已替换 1 处字面匹配')) for m in results):
                    raise AssertionError('跨文件修复失败')
                elif label == 'compile' and not text.startswith(('Exit code: 0', '退出码：0')):
                    raise AssertionError('代理编译失败')
                elif label == 'changes':
                    if any(edit['path'] not in text for edit in server.task['edits']):
                        raise AssertionError('修改报告遗漏已编辑路径')
                elif label in ('sources', 'refresh'):
                    for result, (_, arguments) in zip(results, calls):
                        # read_file 添加行号；要求每一行原始源码都保留。
                        original = server.task['sources'][arguments['path']]
                        if any(line not in result['content'] for line in original.splitlines()):
                            raise AssertionError('当前源码被截断或丢失')
            if index < len(server.steps):
                _, calls = server.steps[index]
                message = {'role': 'assistant', 'content': '', 'tool_calls': [
                    {'id': f'{index}-{n}', 'type': 'function', 'function': {
                        'name': name, 'arguments': json.dumps(arguments)}}
                    for n, (name, arguments) in enumerate(calls)]}
                finish = 'tool_calls'
            else:
                message = {'role': 'assistant', 'content': '已更新相关文件并编译，正确性由独立检查决定。'}
                finish = 'stop'
            encoded = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (AssertionError, KeyError, IndexError) as failure:
            server.failures.append(str(failure))
            self.send_error(422, 'Unprocessable Entity', '夹具工作流验证失败')


def run_case(args, task, variant, repetition):
    budget = args.baseline_budget if variant == 'baseline' else args.compact_budget
    with tempfile.TemporaryDirectory(prefix='harness-multifile-') as temporary:
        workspace = Path(temporary)
        prepare(workspace, task)
        (workspace / 'logs').mkdir()
        for index in range(1, 13):
            (workspace / f'logs/history-{index}.txt').write_text(''.join(
                f'Historical module {index}/Case{line}.java:{line}: obsolete compilation diagnostic seed {index * 83 + line}\n'
                for line in range(90)), encoding='utf-8')
        config = workspace / 'evaluation.yaml'
        config.write_text('provider: openai-compatible\nmodel: multifile-fixture\n'
                          'api-key-env: HARNESS_MULTIFILE_UNUSED\nagent:\n  max-tool-output-chars: 12000\n', encoding='utf-8')
        with ThreadingHTTPServer(('127.0.0.1', 0), Policy) as server:
            server.payloads, server.failures, server.stale_rejected = [], [], False
            server.history_validated, server.discovery_validated = 0, False
            server.task, server.workspace, server.steps = task, workspace, trajectory(task, args)
            server.initial_sources = {name: (workspace / name).read_bytes() for name in task['sources']}
            serving = threading.Thread(target=server.serve_forever, daemon=True)
            serving.start()
            command = [args.java, '-jar', str(JAR), '--config', str(config), '--workspace', str(workspace),
                       '--base-url', f'http://127.0.0.1:{server.server_port}/v1', '--context-budget', str(budget),
                       '--max-iterations', '24', '--parallel-reads', '4', '--allow-commands', '--quiet', '--json',
                       'run', task['prompt'] + ' 检查历史与源码，从过期哈希错误中恢复，更新相关文件并编译。']
            started = time.perf_counter()
            try:
                environment = dict(os.environ)
                environment.pop('HARNESS_MULTIFILE_UNUSED', None)
                completed = subprocess.run(command, capture_output=True, timeout=args.timeout, env=environment)
                elapsed = (time.perf_counter() - started) * 1000
                try:
                    result = json.loads(completed.stdout)
                except (ValueError, UnicodeError):
                    result = {}
                quality = verify(workspace, task, args)
                texts = [json.dumps({'messages': body['messages'], 'tools': body.get('tools', [])},
                                    ensure_ascii=False, separators=(',', ':'), sort_keys=True) for body in server.payloads]
                counted = subprocess.run([args.java, '-cp', str(JAR),
                                          'dev.codeharness.cli.bench.ContextBenchmark', '--count-stdin'],
                                         input=json.dumps(texts, ensure_ascii=False).encode('utf-8'),
                                         capture_output=True, check=True, timeout=60)
                counts = json.loads(counted.stdout)
                edited = {edit['path'] for edit in task['edits']}
                unchanged_count = len(set(task['sources']) - edited)
                unchanged = all((workspace / name).read_bytes() == original
                                for name, original in server.initial_sources.items() if name not in edited)
                passed = (completed.returncode == 0 and result.get('status') == 'COMPLETED'
                          and quality['passed'] and server.stale_rejected and not server.failures and unchanged
                          and unchanged_count > 0 and server.history_validated == 12 and server.discovery_validated
                          and len(server.payloads) == len(server.steps) + 1)
                return {'task': task['id'], 'variant': variant, 'repeat': repetition, 'passed': passed,
                        'status': result.get('status', 'NO_RESULT'), 'exitCode': completed.returncode,
                        'quality': quality, 'staleEditRejectedWithoutMutation': server.stale_rejected,
                        'unrelatedSourcesUnchanged': unchanged, 'workflowFailures': server.failures,
                        'unrelatedSourceCount': unchanged_count, 'historyReadsValidated': server.history_validated,
                        'discoveryValidated': server.discovery_validated,
                        'requests': len(server.payloads), 'iterations': result.get('iterations'),
                        'contextEstimateBudget': budget, 'processElapsedMs': elapsed,
                        'providerUsage': None, 'serializedCl100kTokensSum': sum(c['cl100k'] for c in counts),
                        'serializedO200kTokensSum': sum(c['o200k'] for c in counts),
                        'serializedUtf8BytesSum': sum(c['utf8Bytes'] for c in counts),
                        'sourceSha256': {name: hashlib.sha256((workspace / name).read_bytes()).hexdigest()
                                         for name in task['sources']}}
            except subprocess.TimeoutExpired:
                return {'task': task['id'], 'variant': variant, 'repeat': repetition,
                        'passed': False, 'status': 'TIMEOUT'}
            finally:
                server.shutdown()
                serving.join(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--baseline-budget', type=int, default=48000)
    parser.add_argument('--compact-budget', type=int, default=4000)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--controls-only', action='store_true')
    args = parser.parse_args()
    if not JAR.is_file():
        parser.error('请先使用 mvn verify 构建 shaded jar')
    if not 1 <= args.repeat <= 10 or args.timeout <= 0 or not 0 < args.compact_budget < args.baseline_budget:
        parser.error('要求 repeat 为 1..10、timeout 为正数且 0 < compact-budget < baseline-budget')
    controls = checker_controls(args)
    rows = []
    if not args.controls_only:
        for repetition in range(1, args.repeat + 1):
            order = ('baseline', 'compact') if repetition % 2 else ('compact', 'baseline')
            for task in TASKS:
                for variant in order:
                    rows.append(run_case(args, task, variant, repetition))
    metrics = ('serializedCl100kTokensSum', 'serializedO200kTokensSum', 'serializedUtf8BytesSum')
    pairs = []
    for task in TASKS:
        for repetition in range(1, args.repeat + 1):
            selected = {r['variant']: r for r in rows if r['task'] == task['id'] and r['repeat'] == repetition}
            if not selected:
                continue
            valid = len(selected) == 2 and all(r['passed'] for r in selected.values())
            pairs.append({'task': task['id'], 'repeat': repetition, 'bothPassed': valid,
                          'reductions': {metric: {
                              'baseline': selected['baseline'][metric], 'compact': selected['compact'][metric],
                              'fraction': 1 - selected['compact'][metric] / selected['baseline'][metric]}
                              for metric in metrics} if valid else None})
    report = {'evaluationVersion': 1, 'mode': 'localhost-scripted-multifile',
              'scope': 'Cross-file tool/context/stale-edit recovery with independent compile/assertions; no model coding-quality or production cost/latency claim.',
              'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'artifactSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(),
              'fixtureSha256': hashlib.sha256(json.dumps(TASKS, sort_keys=True).encode()).hexdigest(),
              'controlsOnly': args.controls_only, 'checkerControls': controls,
              'pairedMeasurements': pairs,
              'usageNote': 'Token/byte totals measure canonical messages and schemas, excluding vendor templates; provider usage is absent. No savings threshold is used as a correctness gate.',
              'passed': all(row['passed'] for row in rows), 'runs': rows,
              'summary': {variant: {'passed': sum(r['passed'] for r in rows if r['variant'] == variant),
                                   'runs': sum(r['variant'] == variant for r in rows),
                                   'successfulPairedTotals': {metric: sum(p['reductions'][metric][variant]
                                        for p in pairs if p['bothPassed']) for metric in metrics},
                                   'medianProcessElapsedMs': statistics.median(
                                       r.get('processElapsedMs', args.timeout * 1000) for r in rows if r['variant'] == variant)}
                          for variant in ('baseline', 'compact')} if rows else {}}
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
