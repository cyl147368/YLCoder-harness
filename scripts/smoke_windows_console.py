"""在隐藏的真实 Windows 控制台中验证中文输出，不以 UTF-8 管道解码代替屏幕验证。"""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import traceback

ROOT = Path(__file__).resolve().parents[1]


def console_child(options):
    import msvcrt
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)

    class Coord(ctypes.Structure):
        _fields_ = [('X', wintypes.SHORT), ('Y', wintypes.SHORT)]

    class Rect(ctypes.Structure):
        _fields_ = [('Left', wintypes.SHORT), ('Top', wintypes.SHORT),
                    ('Right', wintypes.SHORT), ('Bottom', wintypes.SHORT)]

    class Info(ctypes.Structure):
        _fields_ = [('size', Coord), ('cursor', Coord), ('attributes', wintypes.WORD),
                    ('window', Rect), ('maximum', Coord)]

    class Character(ctypes.Union):
        _fields_ = [('unicode', wintypes.WCHAR), ('ascii', ctypes.c_char)]

    class Cell(ctypes.Structure):
        _fields_ = [('character', Character), ('attributes', wintypes.WORD)]

    kernel.GetConsoleScreenBufferInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Info)]
    kernel.ReadConsoleOutputW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Cell), Coord, Coord, ctypes.POINTER(Rect)]
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                   wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    for name in ('SetConsoleCP', 'SetConsoleOutputCP'):
        function = getattr(kernel, name)
        function.argtypes = [wintypes.UINT]
        if not function(options.codepage):
            raise ctypes.WinError(ctypes.get_last_error())

    def console_descriptor(name):
        value = kernel.CreateFileW(name, 0xc0000000, 3, None, 3, 0, None)
        if value == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        return msvcrt.open_osfhandle(value, os.O_RDWR | os.O_BINARY)

    # 新控制台的宿主仍可能继承调用器的管道，显式连接控制台设备。
    console_input, console_output = console_descriptor('CONIN$'), console_descriptor('CONOUT$')
    try:
        environment = os.environ.copy()
        # 不向控制台/报告回显宿主的 Java 环境选项。
        for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
            environment.pop(name, None)
        result = subprocess.run([options.launcher, *options.arguments], env=environment, cwd=ROOT,
                                stdin=console_input, stdout=console_output, stderr=console_output, timeout=30)
        handle = msvcrt.get_osfhandle(console_output)
        info = Info()
        if not kernel.GetConsoleScreenBufferInfo(handle, ctypes.byref(info)):
            raise ctypes.WinError(ctypes.get_last_error())
        height = info.cursor.Y + 1
        cells = (Cell * (info.size.X * height))()
        area = Rect(0, 0, info.size.X - 1, height - 1)
        if not kernel.ReadConsoleOutputW(handle, cells, Coord(info.size.X, height), Coord(0, 0), ctypes.byref(area)):
            raise ctypes.WinError(ctypes.get_last_error())
        # 宽字符占两个控制台单元；忽略尾随单元，保留真正的 Unicode 字符。
        lines = []
        for row in range(height):
            lines.append(''.join(cell.character.unicode for cell in cells[row * info.size.X:(row + 1) * info.size.X]
                                 if not cell.attributes & 0x0200).rstrip())
        evidence = {'initialCodepage': options.codepage, 'finalCodepage': kernel.GetConsoleOutputCP(),
                    'exitCode': result.returncode, 'screen': '\n'.join(lines)}
        Path(options.result).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        os.close(console_input)
        os.close(console_output)


def run_console(launcher, codepage, arguments, temporary):
    output = temporary / 'console-result.json'
    output.unlink(missing_ok=True)
    startup = subprocess.STARTUPINFO()
    startup.dwFlags = subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = subprocess.SW_HIDE
    command = [sys.executable, str(Path(__file__).resolve()), '--child', '--launcher', str(launcher),
               '--codepage', str(codepage), '--result', str(output), '--', *arguments]
    child = subprocess.run(command, creationflags=subprocess.CREATE_NEW_CONSOLE,
                           startupinfo=startup, timeout=40)
    assert child.returncode == 0 and output.is_file(), output.read_text(encoding='utf-8') if output.is_file() else '控制台探针失败'
    return json.loads(output.read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child', action='store_true')
    parser.add_argument('--launcher', default=str(ROOT / 'ylc.cmd'))
    parser.add_argument('--codepage', type=int, default=936)
    parser.add_argument('--result', default=str(ROOT / 'target/windows-console.json'))
    parser.add_argument('--record-only', action='store_true')
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    options = parser.parse_args()
    if os.name != 'nt':
        print(json.dumps({'status': 'SKIP', 'reason': '仅适用于 Windows 控制台'}))
        return
    options.launcher = str(Path(options.launcher).resolve())
    if options.arguments[:1] == ['--']:
        options.arguments.pop(0)
    if options.child:
        try:
            console_child(options)
        except Exception as error:
            Path(options.result).write_text(json.dumps({'probeError': repr(error), 'traceback': traceback.format_exc()}, ensure_ascii=False), encoding='utf-8')
            raise
        return
    cases = [(['doctor'], 0, ['厂商适配器：', '此检查不验证厂商连接。API 凭据可从配置文件或环境变量读取。']),
             (['--help'], 0, ['用法：ylc', '选项：', '精简的 Java 编程智能体。']),
             (['--invalid-encoding-option'], 2, ['用法：ylc', '选项：'])]
    results = []
    with tempfile.TemporaryDirectory(prefix='harness-console-') as temporary:
        for codepage in (936, 65001):
            for arguments, exit_code, expected in cases:
                evidence = run_console(options.launcher, codepage, arguments, Path(temporary))
                evidence['arguments'] = arguments
                evidence['passed'] = evidence['exitCode'] == exit_code and all(text in evidence['screen'] for text in expected)
                results.append(evidence)
    report = {'status': 'PASS' if all(row['passed'] for row in results) else 'FAIL',
              'launcher': options.launcher,
              'scope': '隐藏的真实 Windows 控制台输出；读取 Unicode 屏幕缓冲区；无模型请求；不验证键盘输入', 'checks': results}
    Path(options.result).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': report['status'], 'passed': sum(row['passed'] for row in results),
                      'total': len(results), 'report': options.result}))
    if not options.record_only:
        assert report['status'] == 'PASS', '中文控制台回归失败，详见报告'


if __name__ == '__main__':
    main()
