"""在隐藏的真实 Windows 控制台中注入 Unicode 键盘事件，核对任务、工具和审批链路。"""
import argparse
import ctypes
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
FIRST = '看下当前项目，读取 src/中文.txt'
SECOND = '执行一次无害命令并继续'
THIRD = '第三个中文任务 😀 "保留引号"'
FILE_TEXT = '中文文件内容，Unicode 😀。\n'


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == '/v1/chat/completions', self.path
            assert self.headers.get('Authorization') is None
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            scenario = getattr(self.server, 'interrupt_case', None)
            escape_case = getattr(self.server, 'escape_case', None)
            if escape_case in ('model', 'model-rewind', 'stream') and not self.server.requests:
                scenario = 'stream' if escape_case == 'stream' else 'model'
            if scenario in ('model', 'stream'):
                self.server.requests.append(request)
                if scenario == 'stream':
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()
                    event = {'choices': [{'index': 0, 'delta': {'content': '中断流式输出夹具'}, 'finish_reason': None}]}
                    self.wfile.write(('data: ' + json.dumps(event) + '\n\n').encode('utf-8'))
                    self.wfile.flush()
                (self.server.workspace / 'model-ready').touch()
                self.server.release.wait(25)
                return
            assert not request.get('stream')
            self.server.requests.append(request)
            users = [row['content'] for row in request['messages'] if row['role'] == 'user']
            latest = users[-1]
            message = {'role': 'assistant', 'content': '已完成中文任务。'}
            tool = None
            if request['messages'][-1]['role'] == 'tool':
                output = request['messages'][-1]['content']
                if latest == FIRST:
                    assert FILE_TEXT.strip() in output, output
                elif latest == SECOND:
                    assert '退出码：0' in output, output
            elif latest == FIRST:
                tool = ('read_file', {'path': 'src/中文.txt'})
            elif latest == SECOND:
                tool = ('run_command', {'command': 'echo started> tool-started.txt & ping -n 30 127.0.0.1'
                                       if escape_case == 'tool' else 'echo console-approved>> approved.txt'})
            else:
                assert latest == THIRD, repr(latest)
            if tool:
                message['tool_calls'] = [{'id': 'console-' + str(len(self.server.requests)), 'type': 'function',
                                          'function': {'name': tool[0], 'arguments': json.dumps(tool[1], ensure_ascii=False)}}]
                message['content'] = ''
            response = json.dumps({'choices': [{'message': message, 'finish_reason': 'tool_calls' if tool else 'stop'}]},
                                  ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        except Exception as failure:
            self.server.errors.append(repr(failure))
            self.send_error(500, 'console input fixture failed')


def console_child(options):
    import msvcrt
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)

    class Character(ctypes.Union):
        _fields_ = [('unicode', wintypes.WCHAR), ('ascii', ctypes.c_char)]

    class Key(ctypes.Structure):
        _fields_ = [('down', wintypes.BOOL), ('repeat', wintypes.WORD), ('virtual', wintypes.WORD),
                    ('scan', wintypes.WORD), ('character', Character), ('control', wintypes.DWORD)]

    class Event(ctypes.Union):
        _fields_ = [('key', Key), ('padding', ctypes.c_byte * 16)]

    class Record(ctypes.Structure):
        _fields_ = [('type', wintypes.WORD), ('event', Event)]

    class Coord(ctypes.Structure):
        _fields_ = [('X', wintypes.SHORT), ('Y', wintypes.SHORT)]

    class Rect(ctypes.Structure):
        _fields_ = [('Left', wintypes.SHORT), ('Top', wintypes.SHORT), ('Right', wintypes.SHORT), ('Bottom', wintypes.SHORT)]

    class Info(ctypes.Structure):
        _fields_ = [('size', Coord), ('cursor', Coord), ('attributes', wintypes.WORD), ('window', Rect), ('maximum', Coord)]

    class Cell(ctypes.Structure):
        _fields_ = [('character', Character), ('attributes', wintypes.WORD)]

    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                   wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.WriteConsoleInputW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Record), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    kernel.GetConsoleScreenBufferInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Info)]
    kernel.ReadConsoleOutputW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Cell), Coord, Coord, ctypes.POINTER(Rect)]
    kernel.GetConsoleMode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    for setter in ('SetConsoleCP', 'SetConsoleOutputCP'):
        function = getattr(kernel, setter)
        function.argtypes = [wintypes.UINT]
        if not function(options.codepage):
            raise ctypes.WinError(ctypes.get_last_error())

    def descriptor(name):
        value = kernel.CreateFileW(name, 0xc0000000, 3, None, 3, 0, None)
        if value == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        return msvcrt.open_osfhandle(value, os.O_RDWR | os.O_BINARY)

    inp, out = descriptor('CONIN$'), descriptor('CONOUT$')

    def console_modes():
        values = []
        for descriptor in (inp, out):
            mode = wintypes.DWORD()
            assert kernel.GetConsoleMode(msvcrt.get_osfhandle(descriptor), ctypes.byref(mode))
            values.append(mode.value)
        return values

    def screen_text():
        info = Info()
        output_handle = msvcrt.get_osfhandle(out)
        if not kernel.GetConsoleScreenBufferInfo(output_handle, ctypes.byref(info)):
            raise ctypes.WinError(ctypes.get_last_error())
        height = info.cursor.Y + 1
        cells = (Cell * (info.size.X * height))()
        area = Rect(0, 0, info.size.X - 1, height - 1)
        if not kernel.ReadConsoleOutputW(output_handle, cells, Coord(info.size.X, height), Coord(0, 0), ctypes.byref(area)):
            raise ctypes.WinError(ctypes.get_last_error())
        lines = [''.join(cell.character.unicode for cell in cells[row * info.size.X:(row + 1) * info.size.X]
                         if not cell.attributes & 0x0200).rstrip() for row in range(height)]
        return '\n'.join(lines).encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='replace')

    def wait_until(condition):
        deadline = time.monotonic() + 15
        while not condition():
            assert process.poll() is None, ('进程提前退出', process.returncode, screen_text())
            assert time.monotonic() < deadline, ('等待夹具阶段超时', screen_text())
            time.sleep(.05)

    def send_keys(text):
        units = text.encode('utf-16-le')
        records = []
        for offset in range(0, len(units), 2):
            unit = chr(int.from_bytes(units[offset:offset + 2], 'little'))
            for down in (True, False):
                row = Record()
                row.type = 1
                row.event.key.down = down
                row.event.key.repeat = 1
                row.event.key.virtual = 0x43 if unit == '\x03' else 0x0d if unit == '\r' else 0x09 if unit == '\t' else 0x1b if unit == '\x1b' else 0
                row.event.key.control = 0x0008 if unit == '\x03' else 0
                row.event.key.character.unicode = unit
                records.append(row)
        buffer = (Record * len(records))(*records)
        written = wintypes.DWORD()
        if not kernel.WriteConsoleInputW(msvcrt.get_osfhandle(inp), buffer, len(records), ctypes.byref(written)):
            raise ctypes.WinError(ctypes.get_last_error())
        assert written.value == len(records), '键盘事件未完整写入'
        return written.value

    environment = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'YLC_CONSOLE_UNUSED', 'TERM'):
        environment.pop(name, None)
    # CMD 启动器本身会调整控制台模式；以同一启动器的无交互命令作为恢复基线。
    subprocess.run([options.launcher, '--version'], cwd=options.workspace, env=environment,
                   stdin=inp, stdout=out, stderr=out, check=True, timeout=10)
    initial_modes = console_modes()
    kernel.SetConsoleCP(options.codepage)
    kernel.SetConsoleOutputCP(options.codepage)
    # JVM 启动前的 Windows ACP 可能损坏中文 argv；cwd 仍保留真实中文路径。
    command = [options.launcher, '--config', 'offline.yaml',
               '--provider', 'openai-compatible', '--model', 'console-unicode-fixture',
               '--base-url', options.base_url, '--api-key-env', 'YLC_CONSOLE_UNUSED', '--no-stream']
    if options.interrupt_case == 'stream' or options.escape_case == 'stream':
        command.remove('--no-stream')
    if options.interrupt_case or options.escape_case:
        command.append('--no-auto-review')
    if options.escape_case == 'tool':
        command.append('--allow-commands')
    process = subprocess.Popen(command, cwd=options.workspace, env=environment, stdin=inp, stdout=out, stderr=out)
    try:
        # 键盘事件进入控制台输入缓冲区；即使 JVM 尚未进入 readLine 也不会丢失。
        escape_evidence = {}
        if options.escape_case:
            case = options.escape_case
            wait_until(lambda: '你 ›' in screen_text())
            written = 0
            if case in ('rewind', 'model-rewind'):
                written += send_keys('/resume escape-history\r')
                wait_until(lambda: '历史回复二' in screen_text())
            if case == 'idle':
                written += send_keys('不应发送的草稿')
                time.sleep(.15)
                written += send_keys('\x1b')
                wait_until(lambda: screen_text().count('你 ›') >= 2)
            elif case == 'rewind':
                written += send_keys('\x1b\x1b')
            else:
                written += send_keys((SECOND if case in ('approval', 'tool') else THIRD) + '\r')
                if case in ('model', 'stream', 'model-rewind'):
                    wait_until(lambda: (Path(options.workspace) / 'model-ready').exists())
                    if case == 'stream':
                        wait_until(lambda: '中断流式输出夹具' in screen_text())
                    # 转义方向键序列不能取消任务；正常编辑输入也不能触发 Esc。
                    written += send_keys('\x1b[D\x1b[C')
                    time.sleep(.2)
                    assert '已取消当前任务' not in screen_text()
                elif case == 'approval':
                    wait_until(lambda: '[y/N]' in screen_text())
                elif case == 'tool':
                    wait_until(lambda: (Path(options.workspace) / 'tool-started.txt').exists())
                cancelled_at = time.monotonic()
                written += send_keys('\x1b')
                if case == 'model-rewind':
                    time.sleep(.18)
                    written += send_keys('\x1b')
                wait_until(lambda: '已取消当前任务' in screen_text())
                escape_evidence['cancelLatencyMs'] = round((time.monotonic() - cancelled_at) * 1000)
                escape_evidence['cancelledSnapshots'] = [json.loads(p.read_text(encoding='utf-8'))['status']
                                                         for p in (Path(options.workspace) / '.harness/sessions').glob('*.json')]
            if case in ('rewind', 'model-rewind'):
                wait_until(lambda: '回退到 ›' in screen_text())
                original = Path(options.workspace) / '.harness/sessions/escape-history.json'
                original_bytes = original.read_bytes()
                written += send_keys('2\r')
                wait_until(lambda: '已回退到第 2 条提问之前' in screen_text())
                escape_evidence['originalPreserved'] = original_bytes == original.read_bytes()
            # 取消后同一进程仍可使用 Tab、继续模型请求；没有遗留中断标志。
            written += send_keys('/str\toff\r')
            wait_until(lambda: '已关闭流式输出' in screen_text())
            written += send_keys(THIRD + '\r')
            wait_until(lambda: '已完成中文任务。' in screen_text())
            written += send_keys('/exit\r')
        elif options.interrupt_case:
            wait_until(lambda: '›' in screen_text())
            case = options.interrupt_case
            written = send_keys('未提交的中文输入' if case == 'idle' else (SECOND if case == 'approval' else THIRD) + '\r')
            if case in ('model', 'stream'):
                wait_until(lambda: (Path(options.workspace) / 'model-ready').exists())
                if case == 'stream':
                    wait_until(lambda: '中断流式输出夹具' in screen_text())
            elif case == 'approval':
                wait_until(lambda: '[y/N]' in screen_text())
            elif case == 'completed':
                wait_until(lambda: '已完成中文任务。' in screen_text() and screen_text().count('›') >= 2)
            interrupted_at = time.monotonic()
            written += send_keys('\x03')
        else:
            time.sleep(.4)
            prefix = ('/res\t', '/res\t1', '/hist\t', '/str\toff') if options.completion else ()
            written = send_keys('\r'.join((*prefix, FIRST, SECOND, 'y', THIRD, '/exit')) + '\r')
        code = process.wait(timeout=25)
        # UTF-16 代理对先重新组合，报告保持合法 Unicode。
        screen = screen_text()
        evidence = {'initialCodepage': options.codepage, 'finalInputCodepage': kernel.GetConsoleCP(),
                    'finalOutputCodepage': kernel.GetConsoleOutputCP(), 'exitCode': code,
                    'keyboardEventsWritten': written, 'screen': screen}
        if options.escape_case:
            evidence.update(escape_evidence)
            evidence.update({'case': options.escape_case, 'initialConsoleModes': initial_modes, 'finalConsoleModes': console_modes()})
        if options.interrupt_case:
            evidence.update({'case': options.interrupt_case, 'exitLatencyMs': round((time.monotonic() - interrupted_at) * 1000),
                             'initialConsoleModes': initial_modes, 'finalConsoleModes': console_modes()})
        Path(options.result).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        if process.poll() is None:
            # 启动器是 CMD；超时时一并清理其 Java 子进程，避免夹具占用临时工作区。
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            process.wait()
        os.close(inp)
        os.close(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child', action='store_true')
    parser.add_argument('--completion', action='store_true', help='验证真实 Tab 补全、恢复后的历史回显和中文输入。')
    parser.add_argument('--interrupt', action='store_true', help='验证 Ctrl+C 在输入、模型等待、流式输出和审批期间退出。')
    parser.add_argument('--escape', action='store_true', help='验证 Esc 取消后继续和双 Esc 分支回退。')
    parser.add_argument('--escape-case', choices=('idle', 'model', 'stream', 'approval', 'tool', 'rewind', 'model-rewind'))
    parser.add_argument('--interrupt-case', choices=('idle', 'model', 'stream', 'approval', 'completed'))
    parser.add_argument('--launcher', type=Path, default=ROOT / 'ylc.cmd')
    parser.add_argument('--codepage', type=int, default=65001)
    parser.add_argument('--workspace')
    parser.add_argument('--base-url')
    parser.add_argument('--result', type=Path, default=ROOT / 'target/windows-console-input.json')
    options = parser.parse_args()
    if os.name != 'nt':
        print(json.dumps({'status': 'SKIP', 'reason': '仅适用于 Windows 控制台'}))
        return
    options.launcher = options.launcher.resolve()
    if options.child:
        try:
            console_child(options)
        except Exception as error:
            options.result.write_text(json.dumps({'probeError': repr(error), 'traceback': traceback.format_exc()},
                                                ensure_ascii=False, indent=2), encoding='utf-8')
            raise
        return
    results = []
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='console-input-', dir=target) as temporary:
        cases = ('idle', 'model', 'stream', 'approval', 'tool', 'rewind', 'model-rewind') if options.escape else (
            ('idle', 'model', 'stream', 'approval', 'completed') if options.interrupt else (None,))
        for codepage, case in ((cp, case) for cp in (936, 65001) for case in cases):
            workspace = Path(temporary) / f'中文 workspace {codepage} {case or "normal"}'
            (workspace / 'src').mkdir(parents=True)
            (workspace / 'src/中文.txt').write_text(FILE_TEXT, encoding='utf-8')
            (workspace / 'offline.yaml').write_text('{}\n', encoding='utf-8')
            if options.escape and case in ('rewind', 'model-rewind'):
                session_dir = workspace / '.harness/sessions'
                session_dir.mkdir(parents=True)
                (session_dir / 'escape-history.json').write_text(json.dumps({
                    'version': 1, 'id': 'escape-history', 'workspace': str(workspace.resolve()),
                    'provider': 'openai-compatible', 'model': 'console-unicode-fixture',
                    'status': 'COMPLETED', 'updatedAt': '2026-10-19T00:00:00Z',
                    'transcript': [{'role': 'USER', 'content': '历史提问一', 'toolCalls': [], 'providerMetadata': {}},
                                   {'role': 'ASSISTANT', 'content': '历史回复一', 'toolCalls': [], 'providerMetadata': {}},
                                   {'role': 'USER', 'content': '历史提问二', 'toolCalls': [], 'providerMetadata': {}},
                                   {'role': 'ASSISTANT', 'content': '历史回复二', 'toolCalls': [], 'providerMetadata': {}}]
                }, ensure_ascii=False), encoding='utf-8')
            if options.completion:
                session_dir = workspace / '.harness/sessions'
                session_dir.mkdir(parents=True)
                (session_dir / 'tab-history.json').write_text(json.dumps({
                    'version': 1, 'id': 'tab-history', 'workspace': str(workspace.resolve()),
                    'provider': 'openai-compatible', 'model': 'console-unicode-fixture',
                    'status': 'COMPLETED', 'updatedAt': '2026-10-19T00:00:00Z',
                    'transcript': [{'role': 'USER', 'content': '历史提问', 'toolCalls': [], 'providerMetadata': {}},
                                   {'role': 'ASSISTANT', 'content': '历史回复：中文与 Unicode 😀', 'toolCalls': [], 'providerMetadata': {}}]
                }, ensure_ascii=False), encoding='utf-8')
            output = workspace / 'console.json'
            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                server.requests, server.errors = [], []
                server.interrupt_case, server.workspace, server.release = None if options.escape else case, workspace, threading.Event()
                server.escape_case = case if options.escape else None
                serving = threading.Thread(target=server.serve_forever, daemon=True)
                serving.start()
                try:
                    startup = subprocess.STARTUPINFO()
                    startup.dwFlags = subprocess.STARTF_USESHOWWINDOW
                    startup.wShowWindow = subprocess.SW_HIDE
                    command = [sys.executable, str(Path(__file__).resolve()), '--child', '--launcher', str(options.launcher),
                               '--codepage', str(codepage), '--result', str(output), '--workspace', str(workspace),
                               '--base-url', f'http://127.0.0.1:{server.server_port}/v1']
                    if options.completion:
                        command.append('--completion')
                    if case:
                        command.extend(('--escape-case' if options.escape else '--interrupt-case', case))
                    child = subprocess.run(command, creationflags=subprocess.CREATE_NEW_CONSOLE,
                                           startupinfo=startup, capture_output=True, timeout=35)
                finally:
                    server.release.set()
                    server.shutdown()
                    serving.join(5)
            evidence = json.loads(output.read_text(encoding='utf-8')) if output.exists() else {'probeError': child.stderr.decode('utf-8', errors='replace')}
            user_rows = [[row['content'] for row in request['messages'] if row['role'] == 'user'] for request in server.requests]
            checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
            saved_users, saved_workspace = [], None
            if len(checkpoints) == 1:
                state = json.loads(checkpoints[0].read_text(encoding='utf-8'))
                saved_workspace = state['workspace']
                saved_users = [row['content'] for row in state['transcript'] if row['role'] == 'USER']
            approved = (workspace / 'approved.txt').read_text(encoding='utf-8').splitlines() if (workspace / 'approved.txt').exists() else []
            prefix_users = ['历史提问'] if options.completion else []
            expected_users = [prefix_users + values for values in [[FIRST], [FIRST], [FIRST, SECOND], [FIRST, SECOND], [FIRST, SECOND, THIRD]]]
            evidence.update({'providerRequests': len(server.requests), 'wireUserMessages': user_rows,
                             'savedUserMessages': saved_users, 'providerErrors': server.errors,
                             'workspace': str(workspace), 'savedWorkspace': saved_workspace,
                             'approvedCommandLines': approved})
            evidence['passed'] = (child.returncode == 0 and evidence.get('exitCode') == 0 and not server.errors
                                  and user_rows == expected_users
                                  and saved_users == prefix_users + [FIRST, SECOND, THIRD] and approved == ['console-approved']
                                  and saved_workspace is not None and Path(saved_workspace).resolve() == workspace.resolve())
            if options.completion:
                screen = evidence.get('screen', '')
                evidence['passed'] = evidence['passed'] and all(marker in screen for marker in (
                    '已恢复会话：tab-history', '历史回复：中文与 Unicode', '历史提问', '历史对话', '已关闭流式输出'))
                evidence['tabCompletionAndHistory'] = True
            if case and not options.escape:
                expected_saved = [] if case == 'idle' else [SECOND if case == 'approval' else THIRD]
                evidence['savedStatus'] = state['status'] if len(checkpoints) == 1 else None
                evidence['passed'] = (child.returncode == 0 and evidence.get('exitCode') == 130 and not server.errors
                                      and len(server.requests) == (0 if case == 'idle' else 1)
                                      and saved_users == expected_saved and approved == []
                                      and evidence['savedStatus'] == (None if case == 'idle' else 'COMPLETED' if case == 'completed' else 'CANCELLED')
                                      and '已退出会话' in evidence.get('screen', '')
                                      and evidence.get('exitLatencyMs', 25000) < 5000
                                      and evidence.get('initialConsoleModes') == evidence.get('finalConsoleModes'))
                if expected_saved and len(checkpoints) == 1:
                    before_resume = checkpoints[0].read_bytes()
                    environment = os.environ.copy()
                    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'YLC_CONSOLE_UNUSED'):
                        environment.pop(name, None)
                    resumed = subprocess.run([str(options.launcher), '--config', 'offline.yaml', '--provider', 'openai-compatible',
                                              '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                                              '--api-key-env', 'YLC_CONSOLE_UNUSED', '--no-color', '--quiet'],
                                             cwd=workspace, env=environment, input='/resume\n/resume 1\n/exit\n',
                                             capture_output=True, encoding='utf-8', timeout=10)
                    evidence['numericResumePassed'] = (resumed.returncode == 0 and '已恢复会话：' in resumed.stdout
                                                       and before_resume == checkpoints[0].read_bytes())
                    evidence['passed'] = evidence['passed'] and evidence['numericResumePassed']
            if options.escape:
                screen = evidence.get('screen', '')
                expected_last = (['历史提问一', THIRD] if case in ('rewind', 'model-rewind') else
                                 [THIRD] if case == 'idle' else [SECOND, THIRD] if case in ('approval', 'tool') else [THIRD, THIRD])
                evidence['passed'] = (child.returncode == 0 and evidence.get('exitCode') == 0 and not server.errors
                                      and len(user_rows) == (1 if case in ('idle', 'rewind') else 2)
                                      and user_rows[-1] == expected_last and approved == []
                                      and '已完成中文任务。' in screen and '不应发送的草稿' not in str(user_rows)
                                      and evidence.get('initialConsoleModes') == evidence.get('finalConsoleModes'))
                if case not in ('idle', 'rewind'):
                    evidence['passed'] = evidence['passed'] and 'CANCELLED' in evidence.get('cancelledSnapshots', []) and evidence.get('cancelLatencyMs', 25000) < 5000
                if case in ('rewind', 'model-rewind'):
                    evidence['passed'] = evidence['passed'] and evidence.get('originalPreserved', False) and len(checkpoints) == 2
            results.append(evidence)
    report = {'status': 'PASS' if all(row['passed'] for row in results) else 'FAIL', 'launcher': str(options.launcher),
              'windowsACP': ctypes.windll.kernel32.GetACP(),
              'scope': '隐藏的真实 Windows 控制台；WriteConsoleInputW Unicode 键盘事件；localhost 厂商；不模拟物理中文输入法',
              'checks': results}
    options.result.parent.mkdir(parents=True, exist_ok=True)
    options.result.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': report['status'], 'passed': sum(row['passed'] for row in results),
                      'total': len(results), 'report': str(options.result)}))
    assert report['status'] == 'PASS', '控制台中文输入回归失败，详见报告'


if __name__ == '__main__':
    main()
