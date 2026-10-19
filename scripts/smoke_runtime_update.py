"""运行中更新安装，再触发编辑差异的延迟类加载；仅请求 localhost，不读取用户配置。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def install(bundle, destination, config, installer):
    if installer == 'powershell':
        argv = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(bundle / 'install.ps1'),
                '-InstallDirectory', str(destination), '-ConfigDirectory', str(config), '-NoPath']
    else:
        argv = [sys.executable, str(bundle / 'scripts/install.py'), '--install-dir', str(destination),
                '--config-dir', str(config)]
    result = subprocess.run(argv, capture_output=True, timeout=45)
    assert result.returncode == 0, (result.stdout.decode('utf-8', errors='replace'), result.stderr.decode('utf-8', errors='replace'))


def verify(installer, base, legacy_control=False):
    bundle = base / 'bundle'
    for name, source in {'bin/ylc': 'packaging/bin/ylc', 'bin/ylc.cmd': 'packaging/bin/ylc.cmd',
                         'install.ps1': 'install.ps1', 'scripts/install.py': 'scripts/install.py',
                         'config.example.yaml': 'config.example.yaml',
                         'lib/ylc.jar': str(JAR.relative_to(ROOT))}.items():
        target = bundle / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / source, target)
    destination, config_dir = base / 'installed runtime', base / 'configuration'
    legacy = destination / 'lib/ylc.jar'
    legacy.parent.mkdir(parents=True)
    shutil.copyfile(JAR, legacy)
    legacy_time = legacy.stat().st_mtime_ns
    old_hash = digest(JAR)
    install(bundle, destination, config_dir, installer)
    old = destination / 'lib' / (destination / 'lib/current-runtime.txt').read_text().strip()
    old_time = old.stat().st_mtime_ns
    workspace = base / 'workspace with spaces'
    workspace.mkdir()
    pom = workspace / 'pom.xml'
    pom.write_text('<project>\n  <version>old</version>\n</project>\n', encoding='utf-8')
    requested, proceed = threading.Event(), threading.Event()
    requests, failures = [], []

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                if len(requests) == 1:
                    requested.set()
                    assert proceed.wait(45), '安装更新未完成'
                    message = {'role': 'assistant', 'content': '', 'tool_calls': [{
                        'id': 'edit-after-update', 'type': 'function', 'function': {'name': 'edit_file',
                            'arguments': json.dumps({'path': 'pom.xml', 'old_text': '<version>old</version>',
                                                     'new_text': '<version>new</version>'})}}]}
                    finish = 'tool_calls'
                else:
                    outputs = [m['content'] for m in body['messages'] if m['role'] == 'tool']
                    assert len(outputs) == 1 and '--- ' in outputs[0] and '+++ ' in outputs[0], outputs
                    assert '-  <version>old</version>' in outputs[0] and '+  <version>new</version>' in outputs[0], outputs
                    message, finish = {'role': 'assistant', 'content': 'edit verified'}, 'stop'
                data = json.dumps({'choices': [{'message': message, 'finish_reason': finish}]}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except Exception as error:
                failures.append(repr(error))
                self.close_connection = True

    with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        config = config_dir / 'config.yaml'
        config.write_text('{}\n', encoding='utf-8')
        launcher = destination / ('bin/ylc.cmd' if os.name == 'nt' else 'bin/ylc')
        arguments = [str(launcher), '--config', str(config), '--provider', 'openai-compatible',
                     '--base-url', f'http://127.0.0.1:{server.server_port}/v1', '--model', 'update-fixture',
                     '--api-key-env', 'YLC_UPDATE_UNUSED_KEY', '--quiet', '--json', 'run', 'edit pom version']
        process = subprocess.Popen(arguments, cwd=workspace, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            assert requested.wait(20), 'CLI 未进入模型等待状态'
            # 重排 ZIP 偏移，模拟实际重新打包，而不是只在末尾追加条目。
            replacement = base / 'replacement.jar'
            with zipfile.ZipFile(JAR) as source, zipfile.ZipFile(replacement, 'w') as target:
                target.writestr('runtime-update-fixture.txt', 'second runtime')
                for entry in source.infolist():
                    target.writestr(entry, source.read(entry.filename))
            shutil.copyfile(replacement, bundle / 'lib/ylc.jar')
            if legacy_control:
                # 只在隔离夹具中重现旧安装器直接覆盖活动 JAR 的行为。
                shutil.copyfile(replacement, old)
            else:
                install(bundle, destination, config_dir, installer)
                current = destination / 'lib' / (destination / 'lib/current-runtime.txt').read_text().strip()
                assert current != old and digest(current) == digest(replacement)
                assert digest(old) == old_hash and old.stat().st_mtime_ns == old_time, '更新覆盖了正在运行的版本'
                assert digest(legacy) == old_hash and legacy.stat().st_mtime_ns == legacy_time, '迁移覆盖了旧版正在读取的文件'
            proceed.set()
            stdout, stderr = process.communicate(timeout=30)
            if legacy_control:
                error = stderr.decode('utf-8', errors='replace')
                assert process.returncode != 0 and 'NoClassDefFoundError' in error, error
                assert '<version>old</version>' in pom.read_text(encoding='utf-8')
                return {'installer': 'legacy-overwrite-control', 'status': 'PASS',
                        'result': '复现覆盖运行包后的 NoClassDefFoundError；编辑前失败',
                        'error': error.splitlines()[0]}
            assert process.returncode == 0, (stdout.decode('utf-8', errors='replace'), stderr.decode('utf-8', errors='replace'), failures)
            result = json.loads(stdout.decode('utf-8'))
            assert result['status'] == 'COMPLETED' and result['answer'] == 'edit verified', result
            assert '<version>new</version>' in pom.read_text(encoding='utf-8')
            # 新启动器确实使用新包：隐藏旧包和兼容文件后仍可启动。
            old.rename(old.with_suffix('.retained'))
            legacy.rename(legacy.with_suffix('.retained'))
            new_run = subprocess.run([str(launcher), '--help'], cwd=workspace, capture_output=True, timeout=20)
            assert new_run.returncode == 0, new_run.stderr.decode('utf-8', errors='replace')
            assert len(requests) == 2 and not failures, failures
        finally:
            proceed.set()
            if process.poll() is None:
                process.kill()
                process.communicate()
            server.shutdown()
    return {'installer': installer, 'status': 'PASS', 'localhostRequests': len(requests),
            'checks': ['旧版文件迁移不覆盖', '运行中安装新版', '旧进程延迟加载 UnifiedDiff 并完成编辑',
                       '差异包含修改前后内容', '旧运行包哈希及时间不变', '新启动器使用新版']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--result', type=Path)
    parser.add_argument('--legacy-control', action='store_true', help='额外在隔离夹具中复现旧安装器的崩溃')
    args = parser.parse_args()
    records = []
    with tempfile.TemporaryDirectory(prefix='ylc-runtime-update-') as temporary:
        for installer in (['powershell', 'python'] if os.name == 'nt' else ['python']):
            base = Path(temporary) / installer
            base.mkdir()
            records.append(verify(installer, base))
        if args.legacy_control:
            base = Path(temporary) / 'legacy-control'
            base.mkdir()
            records.append(verify('python', base, legacy_control=True))
    result = {'status': 'PASS', 'cases': records, 'scope': '仅本地模拟厂商；不调用真实模型'}
    if args.result:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        args.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
