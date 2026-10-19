"""使用已验证的 shaded jar 和明确列出的公开文件构建便携 ZIP/tar.gz 包。

输入和 SOURCE_DATE_EPOCH 相同时，归档字节可重现。
这不意味着不同 Maven 构建生成相同 jar 字节。不读取凭据或会话。
"""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tarfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOCS = [
    'docs/CODEGO.md', 'docs/benchmarks/codego-acceptance-v1.json',
    'docs/CODEGO_EVALUATION.md', 'docs/benchmarks/codego-acceptance-v2.json',
    'docs/DELIVERY_AUDIT.md', 'docs/VERIFICATION.md',
    'docs/LANGUAGE_TOKENS.md', 'docs/benchmarks/language-corpus-v1.json',
    'docs/benchmarks/language-tokens-v1.json', 'scripts/compare_language_tokens.py',
    'docs/ARCHITECTURE.md', 'docs/PROVIDER_REVIEW.md', 'docs/MCP.md', 'docs/CHANGE_REPORT.md',
    'docs/TOKEN_BUDGET.md', 'docs/NATIVE_STREAMING.md', 'docs/PARALLEL_READ_BENCHMARK.md',
    'docs/BENCHMARKS.md', 'docs/RESEARCH.md', 'docs/DISTRIBUTION.md', 'docs/reviews/context.md',
    'docs/benchmarks/RESULTS.md', 'docs/benchmarks/context-benchmark.json',
    'docs/benchmarks/context-cl100k-v2.json', 'docs/benchmarks/context-o200k-v2.json',
    'docs/benchmarks/context-cl100k-message-metadata.json', 'docs/benchmarks/context-o200k-message-metadata.json',
    'docs/benchmarks/coding-evaluation.json', 'docs/benchmarks/coding-evaluation-revalidation.json',
    'docs/benchmarks/coding-evaluation-native-streaming.json', 'docs/benchmarks/multifile-evaluation-v1.json',
    'docs/benchmarks/parallel-reads-v1.json', 'docs/benchmarks/parallel-reads-v2.json',
]


def main():
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    version = ET.parse(ROOT / 'pom.xml').getroot().findtext('m:version', namespaces=namespace)
    if not version or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version):
        raise ValueError('根 pom.xml 必须声明安全的字面量版本')
    jar = ROOT / f'harness-cli/target/harness-cli-{version}-all.jar'
    with zipfile.ZipFile(jar) as archive:
        for entry in ('dev/codeharness/cli/HarnessCli.class', 'dev/codeharness/cli/tools/UnifiedDiff.class',
                      'dev/codeharness/cli/tools/UnifiedDiff$Lines.class', 'picocli/CommandLine.class',
                      'dev/langchain4j/model/openai/OpenAiChatModel.class'):
            if entry not in archive.namelist():
                raise ValueError('不是完整的 shaded CLI jar：' + entry)
    mapping = {'bin/ylc': 'packaging/bin/ylc', 'bin/ylc.cmd': 'packaging/bin/ylc.cmd',
               'INSTALL.md': 'packaging/INSTALL.md', 'README.md': 'README.md', '使用手册.md': '使用手册.md',
               'config.example.yaml': 'config.example.yaml', 'install.ps1': 'install.ps1',
               'scripts/install.py': 'scripts/install.py', 'scripts/smoke_auto_review.py': 'scripts/smoke_auto_review.py',
               'scripts/smoke_dashboard.py': 'scripts/smoke_dashboard.py',
               'scripts/smoke_bottom_status.py': 'scripts/smoke_bottom_status.py'}
    mapping['configs/codego.example.yaml'] = 'configs/codego.example.yaml'
    mapping.update({name: name for name in DOCS})
    files = {name: (ROOT / source).read_bytes() for name, source in mapping.items()}
    # 批处理启动器使用常规换行；Unix 启动器始终使用 LF。
    files['bin/ylc.cmd'] = files['bin/ylc.cmd'].replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
    files['bin/ylc'] = files['bin/ylc'].replace(b'\r\n', b'\n')
    files['lib/ylc.jar'] = jar.read_bytes()
    manifest = {'version': version, 'files': {name: hashlib.sha256(data).hexdigest()
                                            for name, data in sorted(files.items())}}
    files['manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')
    epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '946684800'))
    if not 315532800 <= epoch <= 4354819199:
        raise ValueError('SOURCE_DATE_EPOCH 必须落在 ZIP 时间戳范围内（1980 年至 2107 年）')
    stamp = time.gmtime(epoch)[:6]
    prefix = 'YLCoder-harness-' + version
    output = ROOT / 'target/distributions'
    output.mkdir(parents=True, exist_ok=True)
    checksums = {}
    for suffix in ('.zip', '.tar.gz'):
        destination = output / (prefix + suffix)
        temporary = destination.with_name(destination.name + '.tmp')
        try:
            if suffix == '.zip':
                with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                    for name, data in sorted(files.items()):
                        info = zipfile.ZipInfo(prefix + '/' + name, stamp)
                        info.create_system = 3
                        info.external_attr = (0o100755 if name == 'bin/ylc' else 0o100644) << 16
                        info.compress_type = zipfile.ZIP_DEFLATED
                        archive.writestr(info, data)
            else:
                with temporary.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=epoch) as compressed:
                    with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as archive:
                        for name, data in sorted(files.items()):
                            info = tarfile.TarInfo(prefix + '/' + name)
                            info.size, info.mtime = len(data), epoch
                            info.mode = 0o755 if name == 'bin/ylc' else 0o644
                            info.uid = info.gid = 0
                            info.uname = info.gname = ''
                            archive.addfile(info, io.BytesIO(data))
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
        checksums[destination.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
    checksum_file = output / 'SHA256SUMS'
    checksum_file.write_text(''.join(f'{digest}  {name}\n' for name, digest in checksums.items()), encoding='utf-8')
    print(json.dumps({'version': version, 'files': len(files), 'output': str(output), 'sha256': checksums}, indent=2))


if __name__ == '__main__':
    main()
