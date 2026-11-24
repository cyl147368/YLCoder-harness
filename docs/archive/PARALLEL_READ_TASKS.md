# 有界并行读取续接

集成更新：核心 API 和批处理实现现已存在。CLI 成员请确保三个显式启用的内置工具在遍历/逐行循环中协作响应中断（受检中断 IOException 可接受；成员中断标记停止协调器）。文件系统读本身仍取决于操作系统。不要扩大到无关修改或 shell 清理工作。

CLI 提示词应鼓励在输入已知时，把独立 read/search/list 请求放到同一模型回合；保留依赖顺序，不把猜测性修改放进批次。只保留一条简洁指令，让能力可用且不显著增加重复提示。

原源码/文档英文要求现按用户最新指令中文化。仅项目，不付费调用。其他成员也在工作，请保留其他修改。不运行 Maven，由负责人串行构建。协作载荷无法读取时以本文件为准。负责人负责核心生产代码和整合，其他互不重叠归属如下。

## 共享核心契约，负责人负责

Tool 增加 `default boolean isParallelSafeRead() { return false; }`。true 声明只读、可线程安全并发读取、协作响应中断。未知/自定义/MCP 工具默认 false。AgentRunOptions 增加第七字段 `int maxParallelReads`，校验 1..8。保留六参数构造器，默认 1，defaults() 仍为 1。核心将连续安全读取调用按最多 maxParallelReads 分批执行，结果/检查点保留调用顺序。不安全工具是顺序屏障：读取不能与修改/命令/未知工具重叠。启动前持久化助手检查点，结果检查点在协调器线程按调用顺序写入。检查点失败/取消会中断运行中读取，不运行后续工具。等待期间轮询取消标记。虚拟执行器清理不能因不协作扩展永久阻塞，不保证强制杀死工具。

## cli_acceptance

负责 HarnessCli.java、HarnessConfig.java、ConfigLoader.java 和测试；WorkspaceTools.java 仅负责 ListFiles/SearchFiles/ReadFile 能力重载及专门能力测试。增加 `--parallel-reads` 和可信 YAML `agent.parallel-reads`，默认 1，范围 1..8，传递 AgentRunOptions 第七字段。模型/MCP 启动前拒绝非法配置/CLI。仅 list_files/search_files/read_file 标 true；write/edit/changes/run_command 和 MCP 保持 false。增加配置/CLI/能力测试。不改核心、README 或其他文档，交接 `docs/PARALLEL_READ_CLI.md` 除外。不运行 Maven。

## responses_acceptance

仅负责新核心测试 `ParallelReadsTest.java` 和交接 `docs/PARALLEL_READ_ACCEPTANCE.md`。按共享契约实现确定性 latch 测试：并发安全读取重叠且不超上限；不安全修改屏障等待前序读取，后序读取观察到修改；即使完成顺序相反，结果/检查点仍按调用順序；默认参数和未分类工具保持串行；检查点失败阻止后续修改；等待中取消会中断读取，阻止后续工具/模型；最大迭代不执行调用。优先有限超时 latch，不用性能阈值。生产代码落地时阅读，交接报告错误，不改生产或运行 Maven。

## gemini_continuity

负责新 CLI 基准类 `harness-cli/src/main/java/dev/codeharness/cli/bench/ToolExecutionBenchmark.java`、必要时专门测试，以及 `docs/PARALLEL_READ_BENCHMARK.md`。独立、离线、可复现 main 使用真实 CodeAgent、脚本模型和自定义安全读取工具，对比 maxParallelReads1 与4。每次读取模拟固定等待（例如80ms），至少4个调用，独立检查重叠/峰值并发、精确有序结果及修改屏障最终状态。记录 p50/p95 耗时（3-5样本）、模拟延迟、最大活跃数、输出正确性、schema/消息/请求数量，准确限定速度结论。向 stdout 输出 JSON，不访问模型网络。不作广泛产品速度结论：这是延迟模拟读取负载，不是真实磁盘/厂商延迟。基准必须能从胖 JAR 运行。负责人负责归档输出、复现脚本和 CI。不运行 Maven。
