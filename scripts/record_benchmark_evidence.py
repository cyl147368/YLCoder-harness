"""归档已完成的本地评估结果，附可重现汇总；不进行外部调用。"""
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
TARGET = ROOT / 'harness-cli/target'
DESTINATION = ROOT / 'docs/benchmarks'


def main():
    coding = json.loads((TARGET / 'coding-evaluation.json').read_text(encoding='utf-8'))
    context = json.loads((TARGET / 'context-benchmark.json').read_text(encoding='utf-8'))
    assert coding['passed'] and coding['mode'] == 'localhost-scripted-trajectory'
    assert len(coding['runs']) == 12 and all(c['rejectsBuggyAndAcceptsRepair'] for c in coding['checkerControls'])
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name in ('coding-evaluation.json', 'context-benchmark.json'):
        shutil.copyfile(TARGET / name, DESTINATION / name)
    baseline = [r for r in coding['runs'] if r['variant'] == 'baseline']
    compact = [r for r in coding['runs'] if r['variant'] == 'compact']
    before = sum(r['serializedCl100kTokensSum'] for r in baseline)
    after = sum(r['serializedCl100kTokensSum'] for r in compact)
    rows = ['# 本地基准证据', '', '在原十小时窗口结束后的 2026-10-14，使用 Windows 11 / Java 21.0.10 记录。本次基准的模型请求均为 localhost，没有付费调用。', '',
            '## 成对脚本化 Java 修复', '',
            'Three tasks, two budgets and two order-alternating repetitions passed independent compile/assertion checks: 12/12. The checker rejected each buggy input and accepted its reference repair. Each actual CLI invocation made 15 mock requests; all retained current source before editing.', '',
            '| 指标 | 基线（估算预算 48000） | 紧凑方案（估算预算 4000） |',
            '|---|---:|---:|',
            f'| 独立检查通过数 | {len(baseline)}/6 | {len(compact)}/6 |',
            f'| 全部六次运行累计序列化 cl100k token 数 | {before:,} | {after:,} |',
            f'| 累计序列化 o200k token 数 | {sum(r["serializedO200kTokensSum"] for r in baseline):,} | {sum(r["serializedO200kTokensSum"] for r in compact):,} |',
            f'| 累计序列化 UTF-8 字节 | {sum(r["serializedUtf8BytesSum"] for r in baseline):,} | {sum(r["serializedUtf8BytesSum"] for r in compact):,} |',
            f'| 本地 CLI 进程耗时中位数，毫秒 | {coding["summary"]["baseline"]["medianProcessElapsedMs"]:.2f} | {coding["summary"]["compact"]["medianProcessElapsedMs"]:.2f} |', '',
            f'这一特定脚本化负载的序列化 cl100k 计数减少了 {100*(1-after/before):.2f}%. This is not billed-token savings, AI coding-quality evidence or a production speedup. The policy uses fixed edits; provider usage is absent. Timing includes local JVM/process/checkpoint overhead and is too limited to infer a general speed difference.', '',
            '## Context microbenchmark', '',
            '| 上下文 | cl100k 前/后 | o200k 前/后 | 压缩 p50/p95 毫秒 |', '|---|---:|---:|---:|']
    for case in context['cases']:
        rows.append(f'| {case["fixture"]} | {case["before"]["serializedCl100kTokens"]:,} / {case["after"]["serializedCl100kTokens"]:,} | {case["before"]["serializedO200kTokens"]:,} / {case["after"]["serializedO200kTokens"]:,} | {case["compactionMs"]["p50"]:.4f} / {case["compactionMs"]["p95"]:.4f} |')
    rows += ['', 'All three cases retained exact instructions, current code, task/call metadata and paired tool results. Input fingerprints and raw metrics are in [context-benchmark.json](context-benchmark.json). The CJK case shows substantial UTF-16 underestimation; the configured estimate is not a tokenizer-enforced limit.', '',
             '原始成对运行：[coding-evaluation.json](coding-evaluation.json)。复现方法、语义和显式启用真实调用的限制：[BENCHMARKS.md](../BENCHMARKS.md)。这些文件不含凭据或私有仓库内容。', '']
    (DESTINATION / 'RESULTS.md').write_text('\n'.join(rows), encoding='utf-8')
    print('\n'.join(rows))


if __name__ == '__main__':
    main()
