"""显式启用自定义 Chat Completions 网关的诊断与小型真实工作流。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

def redact(value, key):
    """JSON 转义前替换精确凭据字符串，也处理字典键。"""
    if isinstance(value, str):
        return value.replace(key, '[redacted]') if key else value
    if isinstance(value, dict):
        return {redact(name, key): redact(item, key) for name, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(item, key) for item in value]
    return value


def workspace_allowed(workspace):
    """仅允许任务源码、配置及常规 harness 运行时状态。"""
    for path in workspace.rglob('*'):
        relative = path.relative_to(workspace)
        if path.is_symlink() or path.resolve() != workspace.resolve().joinpath(relative):
            return False
        if relative.parts[0] == '.harness':
            if not (path.is_file() or path.is_dir()):
                return False
        elif relative.as_posix() not in ('Answer.java', 'task.yaml') or not path.is_file():
            return False
    return all(workspace.joinpath(name).is_file() for name in ('Answer.java', 'task.yaml'))


def workflow_evidence(session, workspace, final_source, final_bytes=None):
    """将真实持久化 TOOL 字符串与对应调用配对；持久化中没有错误布尔值。"""
    pending, seen, results = {}, set(), []
    try:
        transcript = session['transcript']
        if not isinstance(transcript, list):
            return False
        for message in transcript:
            if not isinstance(message, dict):
                return False
            calls = message.get('toolCalls', [])
            if not isinstance(calls, list):
                return False
            if message.get('role') == 'TOOL':
                call = pending.pop(message.get('toolCallId'), None)
                if call is None or calls or message.get('toolName') != call['name']:
                    return False
                content = message.get('content')
                if not isinstance(content, str):
                    return False
                name = call['name']
                if name == 'edit_file':
                    pattern = r'(?:Edited "Answer\.java": replaced 1 literal occurrence; sha256=|已编辑 "Answer\.java": 已替换 1 处字面匹配；sha256=)([0-9a-f]{64})\n'
                    match = re.match(pattern, content)
                    checksum = hashlib.sha256(final_bytes if final_bytes is not None else final_source.encode('utf-8')).hexdigest()
                    if not match or match[1] != checksum:
                        return False
                elif name == 'read_file':
                    # read_file 在每个可见源码行前添加从 1 开始的行号。
                    if not content or any(not re.match(r'[1-9][0-9]*: ', line) for line in content.splitlines()):
                        return False
                results.append((name, content))
            else:
                if pending or (calls and message.get('role') != 'ASSISTANT'):
                    return False
                for call in calls:
                    if not isinstance(call, dict) or call.get('name') not in ('read_file', 'edit_file', 'list_files'):
                        return False  # 不允许命令、write_file、MCP 或无关工具。
                    identity = call.get('id')
                    if not isinstance(identity, str) or not identity or identity in seen:
                        return False
                    arguments = json.loads(call['argumentsJson'])
                    if not isinstance(arguments, dict):
                        return False
                    argument_path = arguments.get('path', '.') if call['name'] == 'list_files' else arguments.get('path')
                    if not isinstance(argument_path, str):
                        return False
                    supplied = Path(argument_path)
                    target = supplied if supplied.is_absolute() else workspace / supplied
                    expected = workspace if call['name'] == 'list_files' else workspace / 'Answer.java'
                    if target.resolve() != expected.resolve():
                        return False
                    seen.add(identity)
                    pending[identity] = call
        if pending:
            return False
        edits = [index for index, (name, _) in enumerate(results) if name == 'edit_file']
        if len(edits) != 1:
            return False
        edit = edits[0]
        before = any(name == 'read_file' and re.search(r'return\s+41\s*;', content)
                     for name, content in results[:edit])
        after = any(name == 'read_file' and re.search(r'return\s+42\s*;', content)
                    for name, content in results[edit + 1:])
        return before and after
    except (KeyError, TypeError, ValueError, OSError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--credential-env', required=True)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--discover', action='store_true')
    parser.add_argument('--model')
    parser.add_argument('--no-stream', action='store_true')
    args = parser.parse_args()
    key = os.getenv(args.credential_env)
    if not args.live or not key: parser.error('需要 --live 和已配置的凭据环境变量')
    if not args.base_url.startswith('https://') or any(c in args.base_url for c in ('?', '#', '@')):
        parser.error('需要显式 HTTPS 基础地址，且不能含凭据或查询')
    report = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'baseUrl': args.base_url, 'mode': 'discovery' if args.discover else 'live-coding',
              'passed': False}
    if args.discover:
        request = urllib.request.Request(args.base_url.rstrip('/') + '/models', headers={
            'Authorization': 'Bearer ' + key, 'Accept': 'application/json', 'User-Agent': 'YLCoder-harness/0.1.0'})
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
                payload = response.read(2_000_001)
                if len(payload)>2_000_000: raise ValueError('响应过大')
                body = json.loads(payload)
                report.update(httpStatus=response.status, models=[m['id'] for m in body.get('data',[]) if isinstance(m, dict) and isinstance(m.get('id'),str)])
                report['passed'] = bool(report['models'])
        except urllib.error.HTTPError as error:
            raw = error.read(4096).decode('utf-8',errors='replace').replace(key,'[redacted]')
            report.update(httpStatus=error.code, contentType=error.headers.get('Content-Type'), server=error.headers.get('Server'),
                          redirectLocation=error.headers.get('Location'), responseExcerpt=raw[:1000])
        except Exception as error: report['errorType']=type(error).__name__
    else:
        if not args.model: parser.error('编码验证必须提供 --model')
        with tempfile.TemporaryDirectory(prefix='harness-codego-live-') as temporary:
            workspace = Path(temporary)
            initial = 'public class Answer { public static int value() { return 41; } }\n'
            (workspace/'Answer.java').write_text(initial,encoding='utf-8')
            config = workspace/'task.yaml'
            config.write_text('provider: openai-compatible\nmodel: '+json.dumps(args.model)+'\nbase-url: '+json.dumps(args.base_url)+'\napi-key-env: '+json.dumps(args.credential_env)+'\ntimeout-seconds: 60\nagent:\n  max-iterations: 5\n  max-output-tokens: 700\npermissions:\n  allow-commands: false\n  allow-mcp-tools: false\n',encoding='utf-8')
            configured_bytes = config.read_bytes()
            command=['java','-jar',str(JAR),'--config',str(config),'--workspace',str(workspace),'--quiet','--json']
            if args.no_stream: command += ['--no-stream']
            command += ['run','读取 Answer.java，使用 edit_file 将 value() 从 41 改为 42，再次读取，然后简要说明修改。不要执行 shell 命令或创建其他文件。']
            started=time.perf_counter()
            try:
                completed=subprocess.run(command,capture_output=True,timeout=180)
                result=json.loads(completed.stdout)
                if not isinstance(result, dict):
                    raise ValueError('CLI 结果无效')
                code_bytes=(workspace/'Answer.java').read_bytes()
                code=code_bytes.decode('utf-8')
                session_id = result['session']
                if not isinstance(session_id, str) or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}', session_id):
                    raise ValueError('会话标识符无效')
                session=json.loads((workspace/'.harness/sessions'/ (session_id+'.json')).read_text(encoding='utf-8'))
                calls=[c['name'] for m in session['transcript'] for c in m.get('toolCalls',[])]
                ordered_workflow = (session.get('id') == session_id and session.get('status') == 'COMPLETED'
                                    and Path(session.get('workspace', '')).resolve() == workspace.resolve()
                                    and workflow_evidence(session, workspace, code, code_bytes))
                allowed_files = workspace_allowed(workspace) and config.read_bytes() == configured_bytes
                # 独立验证器位于代理工作区之外，使用新的源码编译。
                with tempfile.TemporaryDirectory(prefix='harness-codego-check-') as checkdir:
                    checks=Path(checkdir)
                    (checks/'Check.java').write_text('public class Check { public static void main(String[] args) { if (Answer.value()!=42) throw new AssertionError(); System.out.println("CHECKS_PASS"); }}',encoding='utf-8')
                    compiled=subprocess.run(['javac','-proc:none','-classpath',checkdir,'-sourcepath',checkdir,'-d',checkdir,str(workspace/'Answer.java'),str(checks/'Check.java')],capture_output=True,timeout=20)
                    checked=subprocess.run(['java','-cp',checkdir,'Check'],capture_output=True,timeout=20) if compiled.returncode==0 else None
                    quality=checked is not None and checked.returncode==0 and b'CHECKS_PASS' in checked.stdout.splitlines()
                report.update(model=args.model, streaming=not args.no_stream, exitCode=completed.returncode,
                              status=result.get('status'), iterations=result.get('iterations'), usage=result.get('usage'),
                              usageAvailability='adapter-reported; zero may mean missing; no billing verification',
                              elapsedMs=round((time.perf_counter()-started)*1000,3), tools=calls, independentCheck=quality,
                              orderedReadEditRead=ordered_workflow, workspaceAllowlist=allowed_files,
                              finalSourceSha256=hashlib.sha256(code_bytes).hexdigest(),
                              providerError=str(result.get('error','')).replace(key,'[redacted]'))
                report['passed']=completed.returncode==0 and result.get('status')=='COMPLETED' and quality and ordered_workflow and allowed_files
            except Exception as error: report['errorType']=type(error).__name__
    print(json.dumps(redact(report, key),indent=2,ensure_ascii=True).replace(key,'[redacted]'))
    raise SystemExit(0 if report['passed'] else 1)

if __name__=='__main__': main()
