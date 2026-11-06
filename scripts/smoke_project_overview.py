"""用本地模拟厂商、打包 CLI 与 1200 文件工作区验证项目概览及少轮次大历史；不调用真实模型。"""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
import uuid
import traceback
from smoke_context_optimization import ROOT, KEY, SUMMARY_KEY, reply, message

REQUIREMENT = '只读查看项目，不要修改数据库'
SUMMARY = {'goals': ['查看当前项目'], 'constraints': [REQUIREMENT], 'decisions': ['先给项目概览'],
           'completed': ['已查看早期模块'], 'pending': ['检查项目说明和核心入口'], 'failures': [],
           'references': ['src/Source0000.java']}


def launch(java, jar, workspace, home, config, args):
    env = {k: v for k, v in os.environ.items() if k.upper() not in
           {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'}
           and not k.upper().startswith(('HARNESS_', 'YLC_'))}
    env.update(HOME=str(home), USERPROFILE=str(home), XDG_CONFIG_HOME=str(home / '.config'))
    arguments = ['--config', str(config), '--no-color', '--no-web', '--no-stream'] + args
    argfile = workspace / 'overview-cli.args'
    argfile.write_text('\n'.join(json.dumps(a, ensure_ascii=False) for a in arguments), encoding='utf-8')
    started = time.monotonic()
    result = subprocess.run([str(java), '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                             '-Dstderr.encoding=UTF-8', '-Duser.home=' + str(home), '-jar', str(jar), '@' + str(argfile)],
                            cwd=workspace, env=env, input='', text=True, encoding='utf-8', capture_output=True, timeout=120)
    output = result.stdout + result.stderr
    assert KEY not in output and SUMMARY_KEY not in output
    assert result.returncode == 0, output[-6000:]
    return output, round((time.monotonic() - started) * 1000, 2)


def seed_history():
    history = [message('SYSTEM', '旧版本规则'), message('USER', REQUIREMENT)]
    for i in range(14):
        if i in (4, 9):
            history.append(message('USER', '继续查看第' + str(i) + '个模块'))
        assistant = message('ASSISTANT', '读取两个模块')
        calls = [{'id': f'history-{i}-{j}', 'name': 'read_file',
                  'argumentsJson': json.dumps({'path': f'src/Source{i * 2 + j:04d}.java'}), 'providerMetadata': {}}
                 for j in range(2)]
        assistant['toolCalls'] = calls
        history.append(assistant)
        for call in calls:
            result = message('TOOL', ('模块实现资料' * 2000))
            result.update(toolCallId=call['id'], toolName=call['name'])
            history.append(result)
    return history


def scenario(java, jar, protocol, loaded, legacy, evidence_dir):
    requests, errors, main_contents, summary_contents = [], [], [], []
    stages = [0]
    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_POST(self):
            try:
                req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                is_summary = req.get('model') == 'summary-fixture-model'
                assert self.headers.get('Authorization') == 'Bearer ' + (SUMMARY_KEY if is_summary else KEY)
                assert self.path == ('/v1/responses' if protocol == 'responses' else '/v1/chat/completions')
                requests.append(req)
                content = json.dumps(req.get('input', req.get('messages')), ensure_ascii=False)
                if is_summary:
                    summary_contents.append(content)
                    assert not req.get('tools')
                    payload = reply(protocol, json.dumps(SUMMARY, ensure_ascii=False))
                else:
                    main_contents.append(content)
                    stage = stages[0]
                    stages[0] += 1
                    if not legacy:
                        assert '不默认逐文件遍历' in content
                        assert '当前任务意图：analyze' in content
                    if loaded:
                        assert REQUIREMENT in content
                    if stage == 0:
                        payload = reply(protocol, tool=('repository_map', {}))
                    elif stage == 1:
                        assert 'Source0000.java' in content
                        if not legacy:
                            assert '本次读取源码 20' in content and '展示 20/1200' in content
                            assert 'README.md' in content and 'pom.xml' in content
                        payload = reply(protocol, tool=('read_file', {'path': 'README.md'}))
                    elif stage == 2:
                        assert 'README evidence line' in content, '未返回 README 阅读结果'
                        if not legacy:
                            assert '100: README evidence line 100' in content, '默认页没有第 100 行'
                            assert '101: README evidence line 101' not in content
                        payload = reply(protocol, tool=('read_file', {'path': 'pom.xml'}))
                    elif stage == 3:
                        payload = reply(protocol, tool=('read_file', {'path': 'src/Source0000.java'}))
                    elif stage == 4:
                        payload = reply(protocol, '项目概览：示例 Java 工程包含 1200 个源码文件。已抽样项目说明、构建入口与核心源码；没有修改文件，未进行全库审计。')
                    else:
                        raise AssertionError('概览流程发生意外的重复请求')
                data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(200)
            except Exception as failure:
                errors.append(f"stage={stages[0]}: {traceback.format_exc()}")
                data = json.dumps({'error': {'message': str(failure)}}).encode()
                self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix='ylc-overview-', dir=ROOT / 'target') as directory:
            base = Path(directory)
            workspace, home = base / 'work', base / 'home'
            workspace.mkdir(); home.mkdir(); (workspace / 'src').mkdir()
            readme = ''.join(f'README evidence line {i:03d} — project overview and module layout.\n' for i in range(1, 401))
            (workspace / 'README.md').write_text(readme, encoding='utf-8')
            (workspace / 'pom.xml').write_text('<project>\n' + ''.join(f'<!-- build evidence {i} -->\n' for i in range(198)) + '</project>\n', encoding='utf-8')
            for i in range(1200):
                code = f'public class Source{i:04d} {{\n' + ''.join(f'  // source evidence line {line}\n' for line in range(1, 249)) + '}\n'
                (workspace / 'src' / f'Source{i:04d}.java').write_text(code, encoding='utf-8')
            cfg = {'provider': 'openai-compatible', 'api-format': protocol, 'model': 'fixture-model',
                   'base-url': f'http://127.0.0.1:{server.server_port}/v1', 'api-key': KEY,
                   'web': {'enabled': False}, 'agent': {'context-budget-tokens': 50000,
                   'token-counter': 'utf16', 'max-output-tokens': 1000, 'max-tool-output-chars': 12000,
                   'context-recent-turns': 4, 'context-summary-max-tokens': 1500,
                   'context-summarizer': {'model': 'summary-fixture-model', 'api-key': SUMMARY_KEY}}}
            config = base / 'config.json'
            config.write_text(json.dumps(cfg), encoding='utf-8')
            history = seed_history() if loaded else []
            ident = str(uuid.uuid4())
            if loaded:
                sessions = workspace / '.harness/sessions'; sessions.mkdir(parents=True)
                seed = {'version': 1, 'id': ident, 'workspace': str(workspace.resolve()),
                        'provider': 'openai-responses' if protocol == 'responses' else 'openai-compatible',
                        'model': 'fixture-model', 'status': 'COMPLETED', 'updatedAt': datetime.now(timezone.utc).isoformat(),
                        'transcript': history}
                (sessions / (ident + '.json')).write_text(json.dumps(seed, ensure_ascii=False), encoding='utf-8')
            args = ['resume', ident, '看一下当前项目'] if loaded else ['run', '看一下当前项目']
            output, elapsed = launch(java, jar, workspace, home, config, args)
            assert not errors, errors
            assert stages[0] == 5
            assert (workspace / 'README.md').read_text(encoding='utf-8') == readme
            state = list((workspace / '.harness/sessions').glob('*.json'))
            assert len(state) == 1, state
            saved = json.loads(state[0].read_text(encoding='utf-8'))
            assert saved['status'] == 'COMPLETED'
            if loaded:
                assert saved['transcript'][1:len(history)] == history[1:], '持久化完整原文不能被请求投影替换'
            trace = '\n'.join(p.read_text(encoding='utf-8') for p in (workspace / '.harness').rglob('*.md'))
            metrics = [tuple(map(int, values)) for values in re.findall(r'整理前 (\d+)；整理后 (\d+)；消息预算 (\d+)', trace)]
            assert len(metrics) == 5, metrics
            assert all(after <= budget for _, after, budget in metrics)
            local_trims = trace.count('降级：true')
            notices = output.count('本次上下文整理：') if not legacy else output.count('上下文：')
            if not legacy:
                assert local_trims == 0, trace[:5000]
                assert notices == (1 if loaded else 0), output
                assert bool(saved.get('context')) == loaded
                assert bool(summary_contents) == loaded
                if loaded:
                    assert REQUIREMENT in ''.join(summary_contents)
                    assert 'history-13-0' in main_contents[0] and 'history-13-1' in main_contents[0]
                    assert metrics[1][0] < metrics[0][0] / 2, '后续请求应按已复用的投影计量'
                else:
                    assert max(after for _, after, _ in metrics) < 8000
            for path in (workspace / '.harness').rglob('*'):
                if path.is_file() and path.suffix in {'.json', '.md', '.txt'}:
                    text = path.read_text(encoding='utf-8')
                    assert KEY not in text and SUMMARY_KEY not in text
            name = protocol + ('-loaded' if loaded else '-fresh')
            evidence_dir.mkdir(parents=True, exist_ok=True)
            (evidence_dir / (name + '-stdout.txt')).write_text(output, encoding='utf-8')
            return {'protocol': protocol, 'scenario': '少量用户轮次和约八万token工具历史' if loaded else '新会话查看1200文件项目',
                    'status': 'passed', 'main_requests': stages[0], 'summary_requests': len(summary_contents),
                    'tool_calls': 4, 'local_trim_requests': local_trims, 'visible_context_notices': notices,
                    'context_message_estimates': [{'before': a, 'after': b, 'budget': c} for a, b, c in metrics],
                    'summarized_messages': saved.get('context', {}).get('boundary', 0) if saved.get('context') else 0,
                    'history_preserved': True, 'elapsed_ms': elapsed}
    finally:
        server.shutdown(); server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/context-overview-fix/e2e.json')
    parser.add_argument('--legacy-baseline', action='store_true')
    args = parser.parse_args()
    java = Path(os.environ.get('JAVA_HOME', 'D:/software/jdk-21.0.10')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    evidence_dir = args.output.parent / ('baseline-output' if args.legacy_baseline else 'fixed-output')
    rows = [scenario(java, args.jar.resolve(), protocol, loaded, args.legacy_baseline, evidence_dir)
            for protocol in ('chat', 'responses') for loaded in (False, True)]
    result = {'timestamp': datetime.now(timezone.utc).isoformat(), 'network': '仅本地模拟厂商；没有真实模型调用',
              'method': '固定模拟回复驱动真实CLI和工具；本地utf16估算包含消息，工具结构已从预算扣除；未模拟模型自主探索质量',
              'jar_sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(), 'legacy_baseline': args.legacy_baseline, 'rows': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
