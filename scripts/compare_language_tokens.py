"""离线重算相同语义的英文、中文文本 token 数；不访问模型或网络。"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'lib/ylc.jar'
if not JAR.is_file():
    JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
BENCH = 'dev.codeharness.cli.bench.ContextBenchmark'


def difference(english, chinese):
    return {'english': english, 'chinese': chinese, 'difference': chinese - english,
            'changePercent': (chinese / english - 1) * 100 if english else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, default=ROOT / 'docs/benchmarks/language-corpus-v1.json',
                        help='包含英文与中文语义对的固定语料 JSON')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/benchmarks/language-tokens-v1.json',
                        help='离线对比结果 JSON')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    corpus_bytes = args.corpus.read_bytes()
    corpus_sha = hashlib.sha256(corpus_bytes).hexdigest()
    artifact_sha = hashlib.sha256(JAR.read_bytes()).hexdigest()
    script_sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    corpus = json.loads(corpus_bytes)
    entries = corpus['entries']
    if not entries or len({entry['id'] for entry in entries}) != len(entries):
        parser.error('语料不能为空，且 ID 不能重复')
    texts = [entry[language] for entry in entries for language in ('english', 'chinese')]
    counted = subprocess.run([args.java, '-cp', str(JAR), BENCH, '--count-stdin'],
                             input=json.dumps(texts, ensure_ascii=False).encode('utf-8'),
                             capture_output=True, timeout=180, check=True)
    counts = json.loads(counted.stdout)
    assert hashlib.sha256(JAR.read_bytes()).hexdigest() == artifact_sha, '计数期间 jar 已变化，请串行重试'
    assert args.corpus.read_bytes() == corpus_bytes, '计数期间语料已变化，请串行重试'
    assert hashlib.sha256(Path(__file__).read_bytes()).hexdigest() == script_sha, '计数期间脚本已变化，请串行重试'
    assert len(counts) == len(texts), '计数结果数量不一致'
    rows = []
    for index, entry in enumerate(entries):
        english, chinese = counts[2*index:2*index+2]
        row = {'id': entry['id'], 'category': entry['category'],
               'englishSha256': hashlib.sha256(entry['english'].encode('utf-8')).hexdigest(),
               'chineseSha256': hashlib.sha256(entry['chinese'].encode('utf-8')).hexdigest()}
        for encoding in ('cl100k', 'o200k', 'utf8Bytes'):
            row[encoding] = difference(english[encoding], chinese[encoding])
        rows.append(row)
    summaries = {}
    for category in sorted({row['category'] for row in rows}):
        selected = [row for row in rows if row['category'] == category]
        summaries[category] = {'pairs': len(selected)}
        for encoding in ('cl100k', 'o200k', 'utf8Bytes'):
            summaries[category][encoding] = difference(
                sum(row[encoding]['english'] for row in selected),
                sum(row[encoding]['chinese'] for row in selected))
    report = {'version': 1, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'mode': 'offline-exact-local-tokenization',
              'scope': '使用 Jtokkit cl100k_base/o200k_base 对固定英文、中文语料精确本地分词；不代表 CodeGo 计费、模型输出或多轮任务总消耗。文档和注释不会自动作为每次请求的输入。各类别存在内容重叠，不能相加推断单次调用费用。',
              'corpusSha256': corpus_sha,
              'artifactSha256': artifact_sha,
              'scriptSha256': script_sha,
              'capture': corpus.get('capture'), 'summary': summaries, 'entries': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'report': str(args.output), 'summary': summaries}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
