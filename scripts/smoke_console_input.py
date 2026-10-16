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
                tool = ('run_command', {'command': 'echo console-approved>> approved.txt'})
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
    environment = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'YLC_CONSOLE_UNUSED'):
        environment.pop(name, None)
    # JVM 启动前的 Windows ACP 可能损坏中文 argv；cwd 仍保留真实中文路径。
    command = [options.launcher, '--config', 'offline.yaml',
               '--provider', 'openai-compatible', '--model', 'console-unicode-fixture',
               '--base-url', options.base_url, '--api-key-env', 'YLC_CONSOLE_UNUSED', '--no-stream']
    process = subprocess.Popen(command, cwd=options.workspace, env=environment, stdin=inp, stdout=out, stderr=out)
    try:
        # 键盘事件进入控制台输入缓冲区；即使 JVM 尚未进入 readLine 也不会丢失。
        time.sleep(.4)
        text = '\r'.join((FIRST, SECOND, 'y', THIRD, '/exit')) + '\r'
        units = text.encode('utf-16-le')
        records = []
        for offset in range(0, len(units), 2):
            unit = chr(int.from_bytes(units[offset:offset + 2], 'little'))
            for down in (True, False):
                row = Record()
                row.type = 1
                row.event.key.down = down
                row.event.key.repeat = 1
                row.event.key.virtual = 0x0d if unit == '\r' else 0
                row.event.key.character.unicode = unit
                records.append(row)
        buffer = (Record * len(records))(*records)
        written = wintypes.DWORD()
        if not kernel.WriteConsoleInputW(msvcrt.get_osfhandle(inp), buffer, len(records), ctypes.byref(written)):
            raise ctypes.WinError(ctypes.get_last_error())
        assert written.value == len(records), '键盘事件未完整写入'
        code = process.wait(timeout=25)
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
        # UTF-16 代理对先重新组合，报告保持合法 Unicode。
        screen = '\n'.join(lines).encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='replace')
        evidence = {'initialCodepage': options.codepage, 'finalInputCodepage': kernel.GetConsoleCP(),
                    'finalOutputCodepage': kernel.GetConsoleOutputCP(), 'exitCode': code,
                    'keyboardEventsWritten': written.value, 'screen': screen}
        Path(options.result).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(inp)
        os.close(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child', action='store_true')
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
        for codepage in (936, 65001):
            workspace = Path(temporary) / f'中文 workspace {codepage}'
            (workspace / 'src').mkdir(parents=True)
            (workspace / 'src/中文.txt').write_text(FILE_TEXT, encoding='utf-8')
            (workspace / 'offline.yaml').write_text('{}\n', encoding='utf-8')
            output = workspace / 'console.json'
            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                server.requests, server.errors = [], []
                serving = threading.Thread(target=server.serve_forever, daemon=True)
                serving.start()
                try:
                    startup = subprocess.STARTUPINFO()
                    startup.dwFlags = subprocess.STARTF_USESHOWWINDOW
                    startup.wShowWindow = subprocess.SW_HIDE
                    command = [sys.executable, str(Path(__file__).resolve()), '--child', '--launcher', str(options.launcher),
                               '--codepage', str(codepage), '--result', str(output), '--workspace', str(workspace),
                               '--base-url', f'http://127.0.0.1:{server.server_port}/v1']
                    child = subprocess.run(command, creationflags=subprocess.CREATE_NEW_CONSOLE,
                                           startupinfo=startup, capture_output=True, timeout=35)
                finally:
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
            evidence.update({'providerRequests': len(server.requests), 'wireUserMessages': user_rows,
                             'savedUserMessages': saved_users, 'providerErrors': server.errors,
                             'workspace': str(workspace), 'savedWorkspace': saved_workspace,
                             'approvedCommandLines': approved})
            evidence['passed'] = (child.returncode == 0 and evidence.get('exitCode') == 0 and not server.errors
                                  and user_rows == [[FIRST], [FIRST], [FIRST, SECOND], [FIRST, SECOND], [FIRST, SECOND, THIRD]]
                                  and saved_users == [FIRST, SECOND, THIRD] and approved == ['console-approved']
                                  and saved_workspace is not None and Path(saved_workspace).resolve() == workspace.resolve())
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
