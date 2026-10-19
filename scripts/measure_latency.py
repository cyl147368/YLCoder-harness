"""显式启用，测量真实打包 CLI 的首个可见 stdout 和完成时间。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
PHRASE = 'LATENCY_PROBE_OK'
MAX_OUTPUT = 1_048_576
SCRIPT_HASH = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def redact(value, credential):
    if isinstance(value, str):
        return value.replace(credential, '[redacted]') if credential else value
    if isinstance(value, dict):
        return {redact(key, credential): redact(item, credential) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(item, credential) for item in value]
    return value


def parse_cli_usage_summary(stderr):
    """兼容旧双语单行与当前底栏，仅保留一次请求的明确 token 总数。"""
    try:
        text = stderr.decode('utf-8')
    except UnicodeDecodeError:
        return None
    patterns = (
        r'^Session: [A-Za-z0-9_-]+ \| [A-Z_]+ \| 1 calls \| ([0-9]+) tokens \| [0-9]+ ms\r?$',
        r'^会话：[A-Za-z0-9_-]+ \| [A-Z_]+ \| 1 次调用 \| ([0-9]+) tokens \| [0-9]+ ms\r?$',
    )
    legacy = [match for pattern in patterns for match in re.finditer(pattern, text, re.MULTILINE)]
    activities = r'已回复|完成|本轮完成|达到调用上限|已取消|未完成'
    headers_patterns = (
        rf'^  ── 状态 · (?P<activity>{activities}) ──\r?\n',
        rf'^  (?P<activity>{activities})\r?\n',
    )
    body = (
        r'  模型 [^\r\n]+ · 厂商 [^\r\n]+\r?\n'
        r'  本轮  输入 token (?P<input>[^\r\n]+) · 输出 token (?P<output>[^\r\n]+)\r?\n'
        r'  缓存 token [^\r\n]+ · 缓存命中率 [^\r\n]+\r?\n'
        r'  (?P=activity) · (?P<main>[0-9]+)次主模型/(?P<review>[0-9]+)次审核 · '
        r'(?P<tools>未调用工具|[0-9]+次工具) · [0-9]+\.[0-9] 秒'
        r'(?: · 审核复用[0-9]+/人工[0-9]+)?\r?$')
    compact = [match for header in headers_patterns
               for match in re.finditer(header + body, text, re.MULTILINE)]
    headers = len(re.findall(rf'^  (?:── 状态 · |(?:{activities})\r?$)', text, re.MULTILINE))
    if headers:
        # 不从多个任务、部分状态或混合版本的输出中挑选一个看似完整的汇总。
        if headers != 1 or len(compact) != 1 or legacy:
            return None
        summary = compact[0]
        if (summary['main'], summary['review'], summary['tools']) != ('1', '0', '未调用工具'):
            return None
        number = r'(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)'
        values = [summary['input'], summary['output']]
        if not all(re.fullmatch(number, value) for value in values):
            return None  # “未报告”和“部分”均不能作为完整分项相加。
        return sum(int(value.replace(',', '')) for value in values)
    return int(legacy[0][1]) if len(legacy) == 1 else None


def measure_sample(java, base_url, model, credential_env, streaming, timeout=90, on_first_visible=None):
    """内部接口也供受控回环测试使用；外部 CLI 强制 HTTPS 和显式启用。"""
    result = {'requestedMode': 'stream' if streaming else 'sync', 'passed': False,
              'firstVisibleTextMs': None, 'processCompletionMs': None, 'status': 'NO_RESULT',
              'usage': None, 'usageAvailability': 'unavailable', 'errorCategory': None, 'checks': {}}
    try:
        return _measure_sample(java, base_url, model, credential_env, streaming, timeout,
                               on_first_visible, result)
    except OSError:
        # 即使工作区创建、检查或临时文件清理失败，也保留此结果行。
        result['passed'] = False
        result['errorCategory'] = 'measurement_io_failure'
        return result


def _measure_sample(java, base_url, model, credential_env, streaming, timeout, on_first_visible, result):
    if not 1 <= timeout <= 90:
        raise ValueError('样本 timeout 必须为 1..90 秒')
    result['artifactSha256'] = hashlib.sha256(JAR.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix='harness-latency-') as temporary:
        workspace = Path(temporary)
        profile = workspace / 'profile.yaml'
        profile.write_text('provider: openai-compatible\nmodel: ' + json.dumps(model) + '\nbase-url: '
                           + json.dumps(base_url) + '\napi-key-env: ' + json.dumps(credential_env)
                           + '\ntimeout-seconds: ' + str(min(60, timeout))
                           + '\nagent:\n  max-iterations: 1\n  max-output-tokens: 64\npermissions:\n'
                             '  allow-commands: false\n  allow-mcp-tools: false\n', encoding='utf-8')
        profile_bytes = profile.read_bytes()
        result['profileSha256'] = hashlib.sha256(profile_bytes).hexdigest()
        command = [java, '-jar', str(JAR), '--config', str(profile), '--workspace', str(workspace), '--quiet']
        if not streaming:
            command += ['--no-stream']
        command += ['run', '只回复 LATENCY_PROBE_OK。不要调用工具或修改文件。']
        stdout, stderr = bytearray(), bytearray()
        errors, first = [], []
        started = time.monotonic_ns()
        try:
            child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError:
            result['errorCategory'] = 'launch_failure'
            return result

        def read_output(pipe, storage, visible):
            try:
                while True:
                    chunk = pipe.read(1 if visible else 4096)
                    if not chunk:
                        return
                    if visible and not first and chunk not in b' \t\r\n\v\f':
                        first.append(time.monotonic_ns())
                        if on_first_visible is not None:
                            try:
                                on_first_visible()
                            except Exception:
                                errors.append('observer_failure')
                    remaining = MAX_OUTPUT - len(storage)
                    storage.extend(chunk[:max(0, remaining)])
                    if len(chunk) > remaining and 'output_limit' not in errors:
                        errors.append('output_limit')
            except (OSError, ValueError):
                errors.append('reader_failure')

        readers = [threading.Thread(target=read_output, args=(child.stdout, stdout, True), daemon=True),
                   threading.Thread(target=read_output, args=(child.stderr, stderr, False), daemon=True)]
        for reader in readers:
            reader.start()
        try:
            child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            errors.append('sample_timeout')
            try:
                child.kill()
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                errors.append('cleanup_timeout')
            except OSError:
                errors.append('cleanup_failure')
        except OSError:
            errors.append('process_wait_failure')
        finally:
            completed = time.monotonic_ns()
            try:
                if child.poll() is None:
                    child.kill()
            except OSError:
                errors.append('cleanup_failure')
            for reader in readers:
                reader.join(2)
                if reader.is_alive():
                    errors.append('reader_timeout')
            for pipe in (child.stdout, child.stderr):
                if not any(reader.is_alive() for reader in readers):
                    try:
                        pipe.close()
                    except OSError:
                        errors.append('cleanup_failure')
        result['processCompletionMs'] = (completed - started) / 1_000_000
        result['firstVisibleTextMs'] = (first[0] - started) / 1_000_000 if first else None
        result['exitCode'] = child.returncode
        result['cleanupFailure'] = 'cleanup_failure' in errors
        checks = result['checks']
        checks['firstVisibleBytes'] = bool(first)
        checks['validTimingOrder'] = bool(first) and started <= first[0] <= completed
        checks['exactStdout'] = stdout.decode('utf-8', errors='replace').strip() == PHRASE
        checks['profileUnchanged'] = profile.is_file() and profile.read_bytes() == profile_bytes
        checks['workspaceAllowlist'] = True
        for path in workspace.rglob('*'):
            relative = path.relative_to(workspace)
            if (path.is_symlink() or path.resolve() != workspace.resolve().joinpath(relative)
                    or (relative.parts[0] != '.harness' and relative.as_posix() != 'profile.yaml')):
                checks['workspaceAllowlist'] = False
        checkpoints = list(workspace.glob('.harness/sessions/*.json'))
        checks['oneCheckpoint'] = len(checkpoints) == 1
        try:
            session = json.loads(checkpoints[0].read_bytes()) if len(checkpoints) == 1 else {}
            result['status'] = session.get('status', 'NO_RESULT')
            transcript = session.get('transcript', [])
            answers = [m for m in transcript if m.get('role') == 'ASSISTANT']
            checks['oneExactAnswer'] = len(answers) == 1 and answers[0].get('content') == PHRASE
            checks['zeroTools'] = not any(m.get('toolCalls') or m.get('role') == 'TOOL' for m in transcript)
            checks['completed'] = result['status'] == 'COMPLETED'
            checks['sessionIdentity'] = session.get('workspace') == str(workspace.resolve()) and session.get('provider') == 'openai-compatible' and session.get('model') == model
        except (OSError, ValueError, TypeError, AttributeError):
            errors.append('checkpoint_invalid')
            checks['completed'] = False
        # 旧汇总直接提供总数；当前底栏仅在输入、输出均明确且恰好一次主请求时相加。
        # 报告仍只保留总数，不从总数推断分项，也不把缓存重复加进总数。
        total_tokens = parse_cli_usage_summary(stderr)
        if total_tokens is not None:
            result['usage'] = {'totalTokens': total_tokens, 'inputTokens': None, 'outputTokens': None, 'cachedInputTokens': None}
            result['usageAvailability'] = 'CLI adapter-normalized total only; components not retained; legacy zero may mean missing; not billing-verified'
        result['passed'] = child.returncode == 0 and not errors and all(checks.values())
        if errors:
            result['errorCategory'] = errors[0]
        elif not result['passed']:
            if not checks.get('zeroTools', True):
                result['errorCategory'] = 'tool_response_rejected'
            elif result['status'] == 'FAILED':
                result['errorCategory'] = 'agent_failed'
            else:
                result['errorCategory'] = 'acceptance_failed'
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--credential-env', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--repeat', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=90)
    args = parser.parse_args()
    try:
        url = urllib.parse.urlsplit(args.base_url)
        hostname = url.hostname
    except ValueError:
        parser.error('地址 URL 无效')
    if not args.live or url.scheme != 'https' or not hostname or url.username or url.password or url.query or url.fragment:
        parser.error('外部样本需要 --live 及显式 HTTPS 地址，地址不能含用户信息、查询或片段')
    if not 1 <= args.repeat <= 5 or not 1 <= args.timeout <= 90:
        parser.error('repeat 必须为 1..5，timeout 为 1..90')
    credential = os.getenv(args.credential_env)
    if not credential:
        parser.error('必须配置凭据环境变量')
    if not JAR.is_file():
        parser.error('请先构建 shaded CLI 产物')
    rows = []
    for repeat in range(args.repeat):
        for streaming in ((True, False) if repeat % 2 == 0 else (False, True)):
            row = measure_sample(args.java, args.base_url, args.model, args.credential_env, streaming, args.timeout)
            row['repeat'] = repeat + 1
            rows.append(row)
    report = {'measurementVersion': 1, 'passed': all(row['passed'] for row in rows),
              'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'baseUrl': args.base_url, 'model': args.model, 'repeat': args.repeat,
              'scope': 'actual Java CLI subprocess stdout timing; requested mode does not prove SSE transport',
              'runnerSha256': SCRIPT_HASH, 'samples': rows}
    print(json.dumps(redact(report, credential), indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
