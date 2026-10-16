"""在隐藏的真实 Windows 控制台中验证固定底栏、滚动、审批输入与退出恢复。"""
import argparse
import ctypes
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
MODEL = 'bottom-fixture'
FIRST = '显示足够长的第一轮回答'
SECOND = '执行一次需要人工确认的无害命令'
FIRST_END = 'FIRST_LONG_ANSWER_END'
STREAM_CHUNK_END = 'FIRST_STREAM_CHUNK_END'
SECOND_END = 'SECOND_APPROVED_ANSWER_END'
COMMAND = 'echo bottom-approved>> approved.txt'
WIDTH, HEIGHT, SCROLLBACK = 120, 30, 300
ANSWER_LINES = 400
FOOTER_FIELDS = ('模型', MODEL, '厂商', '127.0.0.1', '输入 token',
                 '输出 token', '缓存 token', '缓存命中率')


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def terminate_owned_tree(process):
    # 只清理由本夹具仍持有的活动 PID 及其子进程；cmd 启动器下面还有 JVM。
    if process.poll() is not None:
        return
    subprocess.run(['taskkill.exe', '/PID', str(process.pid), '/T', '/F'],
                   capture_output=True, timeout=10)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == '/v1/chat/completions', self.path
            assert 'Authorization' not in self.headers
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert request['model'] == MODEL, request
            assert bool(request.get('stream')) == self.server.streaming, request
            self.server.requests.append(request)
            cycle = len(self.server.requests)
            users = [message['content'] for message in request['messages'] if message['role'] == 'user']
            tool = None
            if cycle == 1:
                assert users == [FIRST], users
                (self.server.workspace / 'first-request.ready').touch()
                deadline = time.monotonic() + 25
                # 响应被外部文件事件阻塞，子进程必须在它完成前读取真实屏幕底部。
                while not (self.server.workspace / 'first-request.release').exists():
                    assert time.monotonic() < deadline, '未收到实时底栏快照完成事件'
                    time.sleep(.025)
                text = '\n'.join([f'长回答第 {number:03d} 行：正文应在状态栏上方滚动。'
                                  for number in range(1, self.server.answer_lines + 1)] + [FIRST_END])
                inputs, outputs, cache = 120, 20, 60
            elif cycle == 2:
                assert users == [FIRST, SECOND], users
                text = ''
                tool = ('run_command', {'command': COMMAND})
                inputs, outputs, cache = 30, 4, 0
            elif cycle == 3:
                assert users == [FIRST, SECOND], users
                assert request['messages'][-1]['role'] == 'tool'
                assert '退出码：0' in request['messages'][-1]['content']
                text = SECOND_END
                inputs, outputs, cache = 50, 4, 0
            else:
                raise AssertionError('出现未预期的额外请求：' + str(cycle))
            if self.server.streaming:
                assert cycle == 1 and tool is None, '原生流式场景仅执行首轮长回答'
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
                self.send_header('Cache-Control', 'no-cache')
                self.end_headers()

                def event(payload):
                    value = json.dumps(payload, ensure_ascii=False) if isinstance(payload, dict) else payload
                    self.wfile.write(('data: ' + value + '\n\n').encode('utf-8'))
                    self.wfile.flush()

                event({'choices': [{'index': 0, 'delta': {'role': 'assistant'}}]})
                first_chunk = text.rsplit(FIRST_END, 1)[0] + STREAM_CHUNK_END
                event({'choices': [{'index': 0, 'delta': {'content': first_chunk}}]})
                (self.server.workspace / 'stream-chunk.ready').touch()
                deadline = time.monotonic() + 25
                while not (self.server.workspace / 'stream-chunk.release').exists():
                    assert time.monotonic() < deadline, '未收到流式中途屏幕快照完成事件'
                    time.sleep(.025)
                event({'choices': [{'index': 0, 'delta': {'content': '\n' + FIRST_END}}]})
                event({'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]})
                event({'choices': [], 'usage': {'prompt_tokens': inputs, 'completion_tokens': outputs,
                    'prompt_tokens_details': {'cached_tokens': cache}}})
                event('[DONE]')
                return
            message = {'role': 'assistant', 'content': text}
            if tool:
                message['tool_calls'] = [{'id': 'bottom-manual-command', 'type': 'function',
                    'function': {'name': tool[0], 'arguments': json.dumps(tool[1], ensure_ascii=False)}}]
            response = json.dumps({'choices': [{'message': message,
                'finish_reason': 'tool_calls' if tool else 'stop'}],
                'usage': {'prompt_tokens': inputs, 'completion_tokens': outputs,
                          'prompt_tokens_details': {'cached_tokens': cache}}}, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        except Exception as failure:
            self.server.errors.append(repr(failure))
            try:
                self.send_error(500, 'bottom status fixture failed')
            except OSError:
                pass


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
        _fields_ = [('Left', wintypes.SHORT), ('Top', wintypes.SHORT),
                    ('Right', wintypes.SHORT), ('Bottom', wintypes.SHORT)]

    class Info(ctypes.Structure):
        _fields_ = [('size', Coord), ('cursor', Coord), ('attributes', wintypes.WORD),
                    ('window', Rect), ('maximum', Coord)]

    class Cell(ctypes.Structure):
        _fields_ = [('character', Character), ('attributes', wintypes.WORD)]

    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                   wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.WriteConsoleInputW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Record),
                                         wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    kernel.GetConsoleScreenBufferInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Info)]
    kernel.ReadConsoleOutputW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Cell), Coord, Coord, ctypes.POINTER(Rect)]
    kernel.SetConsoleWindowInfo.argtypes = [wintypes.HANDLE, wintypes.BOOL, ctypes.POINTER(Rect)]
    kernel.SetConsoleScreenBufferSize.argtypes = [wintypes.HANDLE, Coord]
    kernel.SetConsoleCursorPosition.argtypes = [wintypes.HANDLE, Coord]
    kernel.WriteConsoleW.argtypes = [wintypes.HANDLE, wintypes.LPCWSTR, wintypes.DWORD,
                                     ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]

    def checked(value):
        if not value:
            raise ctypes.WinError(ctypes.get_last_error())
        return value

    def descriptor(name):
        handle = kernel.CreateFileW(name, 0xc0000000, 3, None, 3, 0, None)
        if handle == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        return msvcrt.open_osfhandle(handle, os.O_RDWR | os.O_BINARY)

    inp, out = descriptor('CONIN$'), descriptor('CONOUT$')
    output_handle = msvcrt.get_osfhandle(out)
    input_handle = msvcrt.get_osfhandle(inp)
    checked(kernel.SetConsoleCP(65001))
    checked(kernel.SetConsoleOutputCP(65001))
    original = Info()
    checked(kernel.GetConsoleScreenBufferInfo(output_handle, ctypes.byref(original)))
    small = Rect(0, 0, min(WIDTH, original.size.X) - 1, min(HEIGHT, original.size.Y) - 1)
    checked(kernel.SetConsoleWindowInfo(output_handle, True, ctypes.byref(small)))
    checked(kernel.SetConsoleScreenBufferSize(output_handle, Coord(WIDTH, SCROLLBACK)))
    viewport = Rect(0, 0, WIDTH - 1, HEIGHT - 1)
    checked(kernel.SetConsoleWindowInfo(output_handle, True, ctypes.byref(viewport)))
    checked(kernel.SetConsoleCursorPosition(output_handle, Coord(0, 0)))

    def snapshot(full_buffer=False):
        info = Info()
        checked(kernel.GetConsoleScreenBufferInfo(output_handle, ctypes.byref(info)))
        width = info.window.Right - info.window.Left + 1
        height = info.window.Bottom - info.window.Top + 1
        area = Rect(info.window.Left, info.window.Top, info.window.Right, info.window.Bottom)
        cells = (Cell * (width * height))()
        checked(kernel.ReadConsoleOutputW(output_handle, cells, Coord(width, height),
                                          Coord(0, 0), ctypes.byref(area)))
        lines = [''.join(cell.character.unicode for cell in cells[row * width:(row + 1) * width]
                         if not cell.attributes & 0x0200).rstrip() for row in range(height)]
        lines = [line.encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='replace')
                 for line in lines]
        # 底栏允许四至五行。其上方的输入提示符属于正文；不能固定剥去五行误吞审批提示。
        footer_start = next((row for row in range(max(0, height - 5), height)
                             if '模型' in lines[row] and MODEL in lines[row] and '厂商' in lines[row]), height - 4)
        result = {'viewportTop': info.window.Top, 'viewportBottom': info.window.Bottom,
                'viewportWidth': width, 'viewportHeight': height,
                'bufferHeight': info.size.Y, 'cursorX': info.cursor.X, 'cursorY': info.cursor.Y,
                'footerHeight': height - footer_start,
                'body': '\n'.join(lines[:footer_start]), 'footer': '\n'.join(lines[footer_start:]),
                'screen': '\n'.join(lines)}
        if full_buffer:
            buffer_width, buffer_height = info.size.X, info.size.Y
            buffer_cells = (Cell * (buffer_width * buffer_height))()
            buffer_area = Rect(0, 0, buffer_width - 1, buffer_height - 1)
            checked(kernel.ReadConsoleOutputW(output_handle, buffer_cells, Coord(buffer_width, buffer_height),
                                              Coord(0, 0), ctypes.byref(buffer_area)))
            buffer_lines = [''.join(cell.character.unicode for cell in
                buffer_cells[row * buffer_width:(row + 1) * buffer_width]
                if not cell.attributes & 0x0200).rstrip() for row in range(buffer_height)]
            result['bufferText'] = '\n'.join(buffer_lines).encode('utf-16-le', errors='surrogatepass').decode(
                'utf-16-le', errors='replace')
        return result

    def has_footer(screen):
        return all(field in screen['footer'] for field in FOOTER_FIELDS)

    def await_screen(predicate, label, seconds=15):
        deadline = time.monotonic() + seconds
        screen = snapshot()
        while time.monotonic() < deadline:
            screen = snapshot()
            if predicate(screen):
                assert screen['viewportWidth'] == WIDTH and screen['viewportHeight'] == HEIGHT, screen
                assert screen['bufferHeight'] > HEIGHT, '夹具必须区分滚动缓冲区与可见屏幕高度'
                return screen
            assert process.poll() is None, ('CLI 在等待屏幕状态前退出', label, process.returncode, screen)
            time.sleep(.04)
        raise AssertionError(('未出现预期的真实控制台状态', label, screen))

    def type_line(text):
        units = (text + '\r').encode('utf-16-le')
        records = []
        for offset in range(0, len(units), 2):
            unit = chr(int.from_bytes(units[offset:offset + 2], 'little'))
            for down in (True, False):
                event = Record()
                event.type = 1
                event.event.key.down = down
                event.event.key.repeat = 1
                event.event.key.virtual = 0x0d if unit == '\r' else 0
                event.event.key.character.unicode = unit
                records.append(event)
        buffer = (Record * len(records))(*records)
        written = wintypes.DWORD()
        checked(kernel.WriteConsoleInputW(input_handle, buffer, len(records), ctypes.byref(written)))
        assert written.value == len(records), '控制台键盘事件未完整写入'

    environment = os.environ.copy()
    # 不伪造终端类型或 VT 环境变量；由 CLI 自行检查真实 CONIN$/CONOUT$ 能力。
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'YLC_BOTTOM_UNUSED',
                 'NO_COLOR', 'TERM', 'WT_SESSION', 'ANSICON', 'ConEmuANSI'):
        environment.pop(name, None)
    workspace = Path(options.workspace)
    # 真实中文 cwd 由 Windows 宽字符进程 API 传递，避免在 Java 原生 argv 中重复传中文路径。
    command = [str(options.launcher), '--config', 'offline.yaml',
               '--provider', 'openai-compatible', '--model', MODEL, '--base-url', options.base_url,
               '--api-key-env', 'YLC_BOTTOM_UNUSED', '--no-auto-review']
    if not options.streaming:
        command.append('--no-stream')
    process = subprocess.Popen(command, cwd=workspace, env=environment, stdin=inp, stdout=out, stderr=out)
    evidence = {'scope': '真实 Windows 控制台；120x30 视口和 300 行滚动缓冲区；localhost 夹具',
                'longAnswerLines': options.answer_lines,
                'streaming': options.streaming,
                'snapshots': {}}

    def persist_snapshot(label):
        screen = snapshot(full_buffer=True)
        evidence['snapshots'][label] = screen
        write_json(options.result, evidence)
        return screen

    def second_task_body(screen):
        marker = '› ' + SECOND
        buffer_text = screen['bufferText']
        assert marker in buffer_text, ('完整缓冲区缺少第二任务输入', screen)
        return buffer_text.split(marker, 1)[1]

    def assert_second_progress_retained(screen):
        body = second_task_body(screen)
        assert SECOND in body, ('正文被清除：第二任务输入缺失', body)
        assert re.search(r'● 正在思考', body), ('正文被清除：第二任务思考进度缺失', body)
        assert re.search(r'● 执行命令', body), ('正文被清除：第二任务工具进度缺失', body)
        assert COMMAND in body, ('正文被清除：完整审批命令缺失', body)
        assert '允许执行命令吗？[y/N]' in body, ('正文被清除：完整审批提示缺失', body)
        return body

    try:
        await_screen(lambda row: has_footer(row) and 'ylc>' in row['body'], '初始底栏')
        initial = persist_snapshot('initial')
        assert '尚无请求' in initial['footer'], initial
        assert initial['body'].splitlines()[-1].strip() == '─' * (initial['viewportWidth'] - 3), initial
        type_line(FIRST)
        ready = workspace / 'first-request.ready'
        await_screen(lambda row: ready.exists() and has_footer(row), '响应阻塞期间的底栏')
        blocked = persist_snapshot('blockedResponse')
        assert not (workspace / 'first-request.release').exists()
        assert FIRST_END not in blocked['body'], '快照必须发生在模型回答完成之前'
        (workspace / 'first-request.release').touch()
        if options.streaming:
            await_screen(lambda row: (workspace / 'stream-chunk.ready').exists()
                         and has_footer(row) and '正在输出' in row['footer']
                         and STREAM_CHUNK_END in row['body'], '长回答流式中途的固定底栏')
            middle = persist_snapshot('streamingPaused')
            assert not (workspace / 'stream-chunk.release').exists(), '中途快照必须先于第二段正文'
            assert FIRST_END not in middle['body'], '最终文本不能提前到达'
            assert '正在输出' in middle['footer'], ('流式中途状态未更新', middle)
            for number in range(max(1, options.answer_lines - 5), options.answer_lines + 1):
                assert f'长回答第 {number:03d} 行' in middle['body'], (
                    '正文被底栏覆盖：流式中途末尾行缺失', number, middle)
            (workspace / 'stream-chunk.release').touch()
        await_screen(lambda row: FIRST_END in row['body'] and has_footer(row)
                     and '120' in row['footer'] and '60' in row['footer'], '长回答后的固定底栏')
        long_answer = persist_snapshot('longAnswer')
        # 超过 300 行会填满缓冲区。只要求末尾仍应可见的正文，不要求已正常滚出的全部历史。
        for number in range(max(1, options.answer_lines - 5), options.answer_lines + 1):
            assert f'长回答第 {number:03d} 行' in long_answer['body'], (
                '正文被底栏覆盖：长回答末尾行缺失', number, long_answer)
        assert FIRST_END in long_answer['body'], ('长回答结束标记不在底栏上方', long_answer)
        if not options.streaming:
            type_line(SECOND)
            await_screen(lambda row: has_footer(row) and '[y/N]' in row['body'], '人工审批输入')
            approval = persist_snapshot('manualApproval')
            assert_second_progress_retained(approval)
            type_line('y')
            await_screen(lambda row: SECOND_END in row['body'] and has_footer(row)
                         and '› ' in row['body'], '第二个任务完成后的底栏')
            completed = persist_snapshot('secondTask')
            second_body = assert_second_progress_retained(completed)
            assert SECOND_END in second_body, ('正文被清除：第二任务回复内容缺失', second_body)
        type_line('/exit')
        evidence['exitCode'] = process.wait(timeout=10)
        assert evidence['exitCode'] == 0, evidence
        # 写满可见屏幕后，最后一行必须能进入原底栏位置，证明退出恢复了滚动区域。
        sentinels = '\r\n' + '\r\n'.join(f'RESTORED_SCROLL_{number:02d}' for number in range(1, 46))
        written = wintypes.DWORD()
        checked(kernel.WriteConsoleW(output_handle, sentinels, len(sentinels), ctypes.byref(written), None))
        restored = persist_snapshot('restoredAfterExit')
        assert 'RESTORED_SCROLL_45' in '\n'.join(restored['screen'].splitlines()[-2:]), restored
        evidence['status'] = 'PASS'
        write_json(options.result, evidence)
    except Exception as failure:
        evidence.update({'status': 'FAIL', 'probeError': repr(failure), 'traceback': traceback.format_exc()})
        try:
            evidence['lastScreen'] = snapshot()
        except Exception as snapshot_failure:
            evidence['snapshotError'] = repr(snapshot_failure)
        write_json(options.result, evidence)
        raise
    finally:
        terminate_owned_tree(process)
        os.close(inp)
        os.close(out)


def verify_bottom_status(launcher=None, result_path=None, answer_lines=ANSWER_LINES, streaming=False):
    if os.name != 'nt':
        return {'status': 'SKIP', 'reason': '固定底栏的真实控制台验收仅适用于 Windows'}
    launcher = Path(launcher or ROOT / 'ylc.cmd').resolve()
    result_path = Path(result_path or ROOT / 'target/bottom-status-console.json')
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    temporary = tempfile.TemporaryDirectory(prefix='bottom-status-', dir=target)
    evidence = {'status': 'FAIL', 'launcher': str(launcher)}
    try:
        workspace = Path(temporary.name) / '中文 workspace'
        workspace.mkdir()
        (workspace / 'offline.yaml').write_text('{}\n', encoding='utf-8')
        output = workspace / 'console.json'
        with FixtureServer(('127.0.0.1', 0), Provider) as server:
            server.requests, server.errors, server.workspace = [], [], workspace
            server.answer_lines = answer_lines
            server.streaming = streaming
            serving = threading.Thread(target=server.serve_forever, daemon=True)
            serving.start()
            startup = subprocess.STARTUPINFO()
            startup.dwFlags = subprocess.STARTF_USESHOWWINDOW
            startup.wShowWindow = subprocess.SW_HIDE
            command = [sys.executable, str(Path(__file__).resolve()), '--child', '--launcher', str(launcher),
                       '--workspace', str(workspace), '--base-url', f'http://127.0.0.1:{server.server_port}/v1',
                       '--result', str(output), '--answer-lines', str(answer_lines)]
            if streaming:
                command.append('--streaming')
            try:
                child = subprocess.run(command, creationflags=subprocess.CREATE_NEW_CONSOLE,
                                       startupinfo=startup, capture_output=True, timeout=70)
            finally:
                server.shutdown()
                serving.join(5)
        evidence = json.loads(output.read_text(encoding='utf-8')) if output.exists() else {
            'probeError': child.stderr.decode('utf-8', errors='replace')}
        approved = workspace / 'approved.txt'
        checkpoints = list((workspace / '.harness/sessions').glob('*.json'))
        saved_workspace, saved_users = None, []
        if len(checkpoints) == 1:
            state = json.loads(checkpoints[0].read_text(encoding='utf-8'))
            saved_workspace = state['workspace']
            saved_users = [message['content'] for message in state['transcript'] if message['role'] == 'USER']
        evidence.update({'launcher': str(launcher), 'localhostRequests': len(server.requests),
                         'windowsACP': ctypes.windll.kernel32.GetACP(),
                         'providerErrors': server.errors,
                         'workspace': str(workspace), 'savedWorkspace': saved_workspace,
                         'savedUserMessages': saved_users,
                         'approvedCommandLines': approved.read_text(encoding='utf-8').splitlines() if approved.exists() else []})
        expected_requests = 1 if streaming else 3
        expected_approved = [] if streaming else ['bottom-approved']
        expected_users = [FIRST] if streaming else [FIRST, SECOND]
        evidence['status'] = 'PASS' if (evidence.get('status') == 'PASS' and child.returncode == 0
            and not server.errors and len(server.requests) == expected_requests
            and evidence['approvedCommandLines'] == expected_approved
            and saved_users == expected_users and saved_workspace is not None
            and Path(saved_workspace).resolve() == workspace.resolve()) else 'FAIL'
        # 先写入项目 target，随后才删除夹具；清理失败不能遮蔽实际的屏幕/进程错误。
        write_json(result_path, evidence)
    except Exception as failure:
        evidence.update({'status': 'FAIL', 'probeError': repr(failure), 'traceback': traceback.format_exc()})
        write_json(result_path, evidence)
    finally:
        try:
            temporary.cleanup()
        except OSError as failure:
            evidence['cleanupError'] = repr(failure)
            evidence['retainedFixture'] = temporary.name
            write_json(result_path, evidence)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选：源代码、安装版或便携版启动器。')
    parser.add_argument('--result', type=Path, default=ROOT / 'target/bottom-status-console.json')
    parser.add_argument('--answer-lines', type=int, default=ANSWER_LINES,
                        help='首轮回答行数；默认 400，覆盖 300 行控制台缓冲区饱和。')
    parser.add_argument('--streaming', action='store_true',
                        help='使用分两段并暂停的 localhost SSE；只执行首轮任务与退出恢复。')
    parser.add_argument('--child', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--workspace', help=argparse.SUPPRESS)
    parser.add_argument('--base-url', help=argparse.SUPPRESS)
    options = parser.parse_args()
    if options.child:
        try:
            console_child(options)
        except Exception as failure:
            evidence = json.loads(options.result.read_text(encoding='utf-8')) if options.result.exists() else {}
            evidence.update({'status': 'FAIL', 'probeError': repr(failure), 'traceback': traceback.format_exc()})
            write_json(options.result, evidence)
            raise
        return
    assert options.answer_lines >= 6, '长回答夹具至少需要六行'
    result = verify_bottom_status(options.launcher, options.result, options.answer_lines, options.streaming)
    write_json(options.result, result)
    print(json.dumps({'status': result['status'], 'localhostRequests': result.get('localhostRequests', 0),
                      'result': str(options.result)}, ensure_ascii=True))
    assert result['status'] in ('PASS', 'SKIP'), '真实控制台底栏回归失败，详见报告'


if __name__ == '__main__':
    main()
