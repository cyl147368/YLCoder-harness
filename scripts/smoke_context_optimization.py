"""通过打包CLI、本地HTTP厂商和隔离工作区验证摘要、恢复、存档、历史检索；无真实模型调用。"""
import argparse
from datetime import datetime, timezone
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
import uuid

ROOT = Path(__file__).resolve().parents[1]
KEY = 'context-fixture-only-key'
SUMMARY_KEY = 'context-fixture-summary-key'
REQUIREMENT = '禁止删除数据库并保留审计记录'
MIDDLE = '工具中间唯一证据CTX-8472'
SUMMARY = {'goals': ['检查登录实现'], 'constraints': [REQUIREMENT], 'decisions': ['保留原接口'],
           'completed': ['已读取原始历史'], 'pending': ['补充测试'], 'failures': [], 'references': ['src/Auth.java']}


def reply(protocol, text='', tool=None):
    if protocol == 'responses':
        output = [{'type': 'message', 'id': 'msg-' + str(uuid.uuid4()), 'status': 'completed', 'role': 'assistant',
                   'content': [{'type': 'output_text', 'text': text}]}]
        if tool:
            name, args = tool
            ident = 'call-' + str(uuid.uuid4())
            output.append({'type': 'function_call', 'id': ident, 'call_id': ident, 'name': name,
                           'status': 'completed', 'arguments': json.dumps(args, ensure_ascii=False)})
        return {'id': 'resp-' + str(uuid.uuid4()), 'status': 'completed', 'output': output,
                'usage': {'input_tokens': 30, 'output_tokens': 12}}
    message = {'role': 'assistant', 'content': text}
    if tool:
        name, args = tool
        message['tool_calls'] = [{'id': 'call-' + str(uuid.uuid4()), 'type': 'function',
                                 'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False)}}]
    return {'choices': [{'message': message, 'finish_reason': 'tool_calls' if tool else 'stop'}],
            'usage': {'prompt_tokens': 30, 'completion_tokens': 12}}


def message(role, text):
    return {'role': role, 'content': text, 'toolCalls': [], 'toolCallId': None, 'toolName': None, 'providerMetadata': {}}


def invoke(java, jar, workspace, home, config, args, input_text=''):
    env = {k: v for k, v in os.environ.items() if k.upper() not in
           {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'} and not k.upper().startswith(('HARNESS_', 'YLC_'))}
    env.update(HOME=str(home), USERPROFILE=str(home), XDG_CONFIG_HOME=str(home / '.config'))
    arguments = ['--config', str(config), '--no-color', '--no-web', '--no-stream', '--quiet'] + args
    argfile = workspace / 'context-cli.args'
    argfile.write_text('\n'.join(json.dumps(a, ensure_ascii=False) for a in arguments), encoding='utf-8')
    started = time.monotonic()
    result = subprocess.run([str(java), '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                             '-Dstderr.encoding=UTF-8', '-Duser.home=' + str(home), '-jar', str(jar), '@' + str(argfile)],
                            cwd=workspace, env=env, input=input_text, text=True, encoding='utf-8', capture_output=True, timeout=90)
    assert KEY not in result.stdout + result.stderr and SUMMARY_KEY not in result.stdout + result.stderr
    assert result.returncode == 0, result.stdout + result.stderr
    payload = json.loads(result.stdout) if '--json' in args else None
    return payload, round((time.monotonic() - started) * 1000, 2), result.stdout


def scenario(java, jar, protocol, malformed=False):
    requests, errors = [], []
    mode = {'value': 'history', 'stage': 0}
    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            try:
                req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                summarizer = req.get('model') == 'summary-fixture-model'
                assert self.headers.get('Authorization') == 'Bearer ' + (SUMMARY_KEY if summarizer else KEY)
                expected = '/v1/responses' if protocol == 'responses' else '/v1/chat/completions'
                assert self.path == expected
                requests.append(req)
                if summarizer:
                    assert not req.get('tools'), '摘要请求不得有工具'
                    content = json.dumps(req.get('input', req.get('messages')), ensure_ascii=False)
                    assert KEY not in content and SUMMARY_KEY not in content
                    payload = reply(protocol, '无效摘要' if malformed else json.dumps(SUMMARY, ensure_ascii=False))
                else:
                    content = json.dumps(req.get('input', req.get('messages')), ensure_ascii=False)
                    assert REQUIREMENT in content or mode['value'] == 'archive'
                    stage = mode['stage']; mode['stage'] += 1
                    if mode['value'] == 'history' and stage == 0:
                        payload = reply(protocol, tool=('search_history', {'query': REQUIREMENT}))
                    elif mode['value'] == 'history' and stage == 1:
                        assert REQUIREMENT in content and '历史检索结果' in content
                        payload = reply(protocol, tool=('repository_map', {'query': 'Auth', 'max_files': 5}))
                    elif mode['value'] == 'history' and stage == 2:
                        assert 'Auth.java' in content and 'Auth' in content
                        payload = reply(protocol, '关键要求已找回，地图定位成功。')
                    elif mode['value'] == 'archive' and stage == 0:
                        payload = reply(protocol, tool=('read_file', {'path': 'large.txt', 'start_line': 1, 'end_line': 500}))
                    elif mode['value'] == 'archive' and stage == 1:
                        match = re.search(r'archive_id=([0-9a-f]{64})', content)
                        assert match, content[-2000:]
                        payload = reply(protocol, tool=('read_tool_output', {'archive_id': match.group(1), 'start_line': 249, 'end_line': 251}))
                    elif mode['value'] == 'archive' and stage == 2:
                        assert MIDDLE in content
                        payload = reply(protocol, '工具中间证据已读取。')
                    else:
                        payload = reply(protocol, '恢复并复用成功。')
                data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            except Exception as exc:
                errors.append(str(exc)); data = json.dumps({'error': {'message': str(exc)}}).encode()
                self.send_response(500); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='ylc-context-', dir=ROOT / 'target') as temp:
            base = Path(temp); workspace = base / 'work'; home = base / 'home'; workspace.mkdir(); home.mkdir()
            (workspace / 'src').mkdir(); (workspace / 'src/Auth.java').write_text('public class Auth {}', encoding='utf-8')
            config = base / 'config.json'
            cfg = {'provider': 'openai-compatible', 'api-format': protocol, 'model': 'fixture-model',
                   'base-url': 'http://127.0.0.1:' + str(server.server_port) + '/v1', 'api-key': KEY,
                   'web': {'enabled': False}, 'agent': {'context-budget-tokens': 10000,
                   'token-counter': 'utf16', 'max-output-tokens': 1000, 'max-tool-output-chars': 1000,
                   'context-recent-turns': 1, 'context-summary-max-tokens': 1500,
                   'context-summarizer': {'model': 'summary-fixture-model', 'api-key': SUMMARY_KEY}}}
            config.write_text(json.dumps(cfg), encoding='utf-8')
            sessions = workspace / '.harness/sessions'; sessions.mkdir(parents=True)
            ident = str(uuid.uuid4()); history = [message('SYSTEM', '原规则'), message('USER', '检查登录实现')]
            for i in range(12):
                history.extend([message('USER', REQUIREMENT if i == 4 else '检查阶段' + str(i)),
                                message('ASSISTANT', '原始历史记录与检查资料。' * 1300)])
            seed = {'version': 1, 'id': ident, 'workspace': str(workspace.resolve()), 'provider': 'openai-compatible',
                    'model': 'fixture-model', 'status': 'COMPLETED', 'updatedAt': datetime.now(timezone.utc).isoformat(), 'transcript': history}
            # Responses 会话厂商标识由 api-format 转換，与当前连接保持一致。
            seed['provider'] = 'openai-responses' if protocol == 'responses' else 'openai-compatible'
            (sessions / (ident + '.json')).write_text(json.dumps(seed, ensure_ascii=False), encoding='utf-8')
            result, elapsed, _ = invoke(java, jar, workspace, home, config, ['resume', ident, '检查并继续', '--json'])
            assert result['status'] == 'COMPLETED', result
            saved = json.loads((sessions / (ident + '.json')).read_text(encoding='utf-8'))
            assert len(saved['transcript']) >= len(history) and history[10] in saved['transcript']
            assert bool(saved.get('context')) != malformed
            trace = Path(result['traceFile']).read_text(encoding='utf-8')
            (ROOT / 'target/context-e2e-debug-trace.md').write_text(trace, encoding='utf-8')
            (ROOT / 'target/context-e2e-debug-requests.json').write_text(json.dumps(requests, ensure_ascii=False, indent=2), encoding='utf-8')
            assert '上下文整理' in trace and 'summarizer' in trace
            assert ('降级：true' in trace) if malformed else ('已生成结构化摘要' in trace)
            summary_calls = sum(r.get('model') == 'summary-fixture-model' for r in requests)
            if not malformed:
                assert REQUIREMENT in json.dumps([r for r in requests if r.get('model') == 'summary-fixture-model'], ensure_ascii=False)
                mode['value'] = 'resume'; mode['stage'] = 0
                cfg['model'] = 'second-fixture-model'; config.write_text(json.dumps(cfg), encoding='utf-8')
                second, resume_ms, _ = invoke(java, jar, workspace, home, config, ['--model', 'second-fixture-model', 'resume', ident, '继续', '--json'])
                assert sum(r.get('model') == 'summary-fixture-model' for r in requests) == summary_calls
                assert requests[-1]['model'] == 'second-fixture-model'
                assert '复用：true' in Path(second['traceFile']).read_text(encoding='utf-8')
                # 同一会话回退保留id与快照、清除投影，并把输入回显交互行。
                before_rewind = len(json.loads((sessions / (ident + '.json')).read_text(encoding='utf-8'))['transcript'])
                _, _, output = invoke(java, jar, workspace, home, config, [], '/resume ' + ident + '\n/rewind 2\n/exit\n')
                rewound = json.loads((sessions / (ident + '.json')).read_text(encoding='utf-8'))
                assert rewound['id'] == ident and len(rewound['transcript']) < before_rewind
                assert not rewound.get('context') and rewound.get('rewinds')
            else:
                resume_ms = None
                assert summary_calls <= 8, '失败冷却没有生效'
            mode['value'] = 'archive'; mode['stage'] = 0
            large = '\n'.join(MIDDLE if i == 250 else f'{i}: ' + '工具详情数据' * 30 for i in range(1, 501))
            (workspace / 'large.txt').write_text(large, encoding='utf-8')
            archive_result, archive_ms, _ = invoke(java, jar, workspace, home, config, ['run', '读取large.txt并分页找回中间证据', '--json'])
            assert archive_result['status'] == 'COMPLETED', archive_result
            archives = list((workspace / '.harness/artifacts').glob('*.txt'))
            assert archives and any(MIDDLE in p.read_text(encoding='utf-8') for p in archives)
            assert not errors, errors
            for p in (workspace / '.harness').rglob('*'):
                if p.is_file() and p.suffix in {'.json', '.md', '.txt'}:
                    text = p.read_text(encoding='utf-8'); assert KEY not in text and SUMMARY_KEY not in text
            return {'protocol': protocol, 'malformed_summary': malformed, 'status': 'passed',
                    'summary_requests': summary_calls, 'requests': len(requests), 'first_run_ms': elapsed,
                    'resume_new_jvm_ms': resume_ms, 'archive_run_ms': archive_ms, 'archive_files': len(archives)}
    finally:
        server.shutdown(); server.server_close()


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--jar', type=Path, default=ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/context-e2e.json'); args = parser.parse_args()
    java = Path(os.environ.get('JAVA_HOME', 'D:/software/jdk-21.0.10')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    rows = [scenario(java, args.jar.resolve(), protocol, malformed) for protocol in ('chat', 'responses') for malformed in (False, True)]
    evidence = {'timestamp': datetime.now(timezone.utc).isoformat(), 'network': '仅127.0.0.1模拟API，无真实厂商调用', 'rows': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
