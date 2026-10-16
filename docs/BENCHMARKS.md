# 可复现的效率评测

已纳入仓库的证据包括确定性的 localhost 对照组和经用户授权的 CodeGo 模型实测。每份报告都标明模式。本地分词计数不能证明厂商账单节省；本地进程耗时不能预测生成延迟。脚本化修复验证 harness 行为；真实小型任务只证明其测得的编程结果。当前实测协议和限制见 [CodeGo 评测](CODEGO_EVALUATION.md)。

## 上下文微基准

先构建，再运行：

```shell
java -cp harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar dev.codeharness.cli.bench.ContextBenchmark
java -cp harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar dev.codeharness.cli.bench.ContextBenchmark --token-counter cl100k
java -cp harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar dev.codeharness.cli.bench.ContextBenchmark --token-counter o200k
```

带版本的确定性夹具包含 30 个历史工具循环和一次最近的源码读取，覆盖 Java 代码、编译诊断和 CJK 文本。报告包含输入 SHA-256、消息数、harness 选定的本地统计计数、UTF-8 序列化字节数，以及 Jtokkit 1.1.0 的 `cl100k_base`/`o200k_base` 载荷计数。分词器看到的是固定的、厂商无关的消息/schema JSON 序列化，不是厂商内部聊天模板。两个载荷计数均含固定 schema；1200 预算仅覆盖消息统计。精确的系统/原始/最近用户文本、当前代码、工具参数/不透明元数据和结果配对必须保留。预热五次后测量压缩 25 次；p50 和最近秩 p95 包含压缩与选定计数器工作，不包含 JVM/编码启动和最终报告的分词/序列化。这是本地经过时间，不是 CPU 利用率或模型耗时。

默认 harness 计数器按每 token 四个 UTF-16 代码单元近似计算。CJK 和厂商开销可能超过估算；它不是严格分词器上限。第 2 版接受 `--token-counter`，用于测量已实现的本地分词策略。messageEstimate 字段反映选定计数器，1200 预算仍不包括基准的固定 schema。分词器模式的热缓存计时包含计数缓存命中和新构造的摘要记录，不包含冷编码加载与最终全载荷报告分词。已归档的第 1 版仍作为历史 UTF-16 证据。详见[计数器语义](TOKEN_BUDGET.md)。压缩有意丢弃旧细节；保留检查不能证明所有省略信息都无必要。

## 打包后的编程评测

```shell
python scripts/evaluate_coding.py --repeat 2
```

实际 shaded CLI 在独立临时工作区执行三个任务：包含边界的范围、空数组/小数平均值、null/空白处理。每个任务使用固定的 localhost 工具轨迹：12 次有界历史读取、一次当前源码读取和一次字面量编辑。独立 `javac`/`java` 断言程序评估最终代码。若相关源码从实际发出的上下文中消失，模拟服务会拒绝编辑请求。基线与压缩变体只在上下文预算上不同（48000 对 4000）；工具输出上限相同。奇数次先运行基线，偶数次反转顺序。每次运行报告状态、独立质量检查、两种分词器的累计序列化消息/schema 计数、字节数，以及本地进程/智能体耗时。模拟响应不提供用量，报告将厂商用量设为 null。

这里比较的是两个上下文预算，不是无限制的实现。只有基线预算/输出上限允许，旧消息才会留在基线中。脚本化策略选择固定编辑；断言通过证明该工作负载下 harness/上下文/工具的正确性，不证明推理质量。JVM 冷启动、文件系统/检查点工作、服务调度和机器负载会影响本地时间。不设置速度阈值。

## 打包后的多文件工作流评测

```shell
python scripts/evaluate_multifile.py --repeat 2
python scripts/evaluate_multifile.py --controls-only
```

这个独立的纯离线套件包含三类关联文件变更：公开 API 重命名/调用方迁移、long 发票汇总/格式化、字面量分隔符解析/必填字段验证。每项任务至少实际编辑两个文件，并包含一个必须保持逐字节一致的无关源码。每次运行读取 12 个历史诊断文件，发现并读取当前源码，尝试一次故意过期的哈希编辑，确认未修改，重新读取并修复关联文件，通过 `run_command` 编译，最后取得 `changes`。localhost 策略立即验证每次历史读取、必需源码保留、最新结果的顺序和编译/差异行为。它提供已知修复，不从源码推理修复方案。

独立检查器仅在 harness 退出后创建，在新目录中编译所有任务源码，显式设置 classpath/sourcepath，并关闭注解处理。智能体生成的 class 文件不参与验证。对照组验证有缺陷、完全修复和每种省略编辑的变体；若单靠编译可能误判，还会直接检查 API 契约。这是依赖隔离，不是恶意代码沙箱。过期编辑恢复不能证明检查点/崩溃恢复能力。

两种变体使用相同的工具输出上限和脚本化轨迹，只在 48000/4000 上下文估算预算上不同，并在重复运行间交替顺序。报告包含产物/夹具哈希、实测请求载荷 token/字节总数、每对的减少比例、成功配对汇总和本地经过时间。失败或未完成的配对不进入减少量计算。正向节省是测量结果，不是正确性门槛；没有厂商用量。脚本化正确性与进程计时均不能证明模型质量、厂商账单节省或生产速度。

## 显式授权的真实回放

同一运行器只有在提供 `--live`、显式可信配置、厂商/模型和非空凭据环境变量后，才支持真实编程尝试。CodeGo 实测报告与 localhost 报告分开归档。

```shell
python scripts/evaluate_coding.py --live --config task-config.yaml --provider openai --model YOUR_MODEL --credential-env OPENAI_API_KEY --repeat 3
```

凭据名称必须与已审阅配置中的 `api-key-env` 一致。两种变体恢复相同的预置历史诊断工具循环和当前源码，再尝试相同修复。临时工作区保证任务独立。独立检查器在智能体退出后运行，不提前暴露。模型生成的 Java 会在宿主机上编译并执行；真实评测应使用合适的隔离环境。可信配置还可能启用命令或外部工具，因此运行前请审阅。运行器本身不授予命令/外部工具权限。

报告包含适配器累计的输入/输出/缓存用量、质量、迭代次数和耗时。零用量可能表示厂商未提供计数，不能报告为免费。此 JSON 运行器不测量首文本延迟或实际传输 token 估算。`scripts/measure_latency.py` 单独记录真实 CLI stdout 的时间戳；`scripts/check_latency_measurement.py` 离线验证提前输出和失败保留。实际样本、模式限定和宿主负载限制见 [CodeGo 评测](CODEGO_EVALUATION.md)。重复请求可能命中预热提示词缓存；应单独比较缓存输入，并在报告之外记录厂商价格。

小型真实回放任务只是起步评测套件。独立离线多文件套件扩大了工具/上下文覆盖；宣称在相同编程质量下有生产节省之前，仍需更广泛的真实仓库和真实模型回归检查。

已归档的本地输出和实测结果见 [benchmarks/RESULTS.md](benchmarks/RESULTS.md)。刷新证据时，将上下文基准输出重定向到 `harness-cli/target/context-benchmark.json`，将 `--repeat 2` 编程评测输出重定向到 `harness-cli/target/coding-evaluation.json`，再运行 `python scripts/record_benchmark_evidence.py`。归档脚本仅接受通过的 localhost 报告，要求 12 次运行和已验证的检查器对照组。
