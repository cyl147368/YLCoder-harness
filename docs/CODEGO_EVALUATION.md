# CodeGo 实测验收

本次续接使用明确获授权的 `https://codegoai.com/v1` 网关和已部署模型 `gpt-6-sol`。原始十小时窗口未达成。这些证据不宣称十小时持续计算。凭据只存在于临时进程环境，不进入源码、配置、结果或发行包。

以下评测脚本需要源码目录和已构建的 shaded CLI jar，与便携运行启动器分开。每条 `--live` 命令都是显式真实调用，可能产生网关费用；离线对照从不使用外部凭据。

## 顺序平衡的预置历史修复

```shell
python scripts/evaluate_coding.py --live --config configs/codego.example.yaml --provider openai-compatible --model gpt-6-sol --credential-env CODEGO_API_KEY --repeat 2 --max-iterations 6 --timeout 180
```

三个单文件 Java 任务使用相同的十二个预置历史诊断循环和当前源码。基线/压缩上下文估算预算为 48000/4000。奇数重复先运行基线，偶数重复先运行压缩。每次尝试允许六次调用，不自动重试付费请求。私有独立检查器在退出后运行，正/负对照先于套件运行。十二次尝试全部完成并通过断言，汇总包含六个匹配配对。

| 测量项 | 基线 | 压缩 |
| --- | ---: | ---: |
| 报告的输入 tokens | 352817 | 98203 |
| 报告的缓存输入 tokens | 326784 | 93696 |
| 报告的输出 tokens | 1648 | 1858 |
| CLI 进程耗时中位数（ms） | 17323.625 | 16240.125 |

72.17% 输入减少仅适用于此预置历史回放工作负载。缓存输入是输入的一部分，不是额外计数。网关计数未经过独立账单审计。更紧凑的上下文有时需要更多调用；报告保留了一次失败编辑和不同但等价的源码哈希。全部隐藏断言通过，证明的是夹具实测质量，不是广泛仓库质量。小幅时间差不能证明稳定提速。

## 真实 CLI 首个可见文本

```shell
python scripts/measure_latency.py --live --base-url https://codegoai.com/v1 --model gpt-6-sol --credential-env CODEGO_API_KEY --repeat 3 --timeout 90
python scripts/check_latency_measurement.py
```

六个真实样本交替采用 stream/sync 请求顺序。单调时钟读取器以子进程启动为起点，记录实际 Java CLI 发出首个非空白 stdout 字节的时间。进程完成时间单独记录；测量包含 JVM 启动、配置/检查点工作、网络/模型延迟、输出刷新和 Python 调度。每个新任务要求精确输出 `LATENCY_PROBE_OK`，仅允许一次迭代和 64 个输出 tokens，禁止命令/MCP。验收要求精确答案/stdout、匹配的 COMPLETED 会话、无工具调用/结果、配置未变和工作区允许路径检查通过。

最初六个样本全部通过。流式请求首文本中位数为 7645.234 ms，同步为 6545.353 ms；完成时间中位数为 8161.276/7050.436 ms。初始序列可能与独立编译器/localhost 夹具活动重叠，仍存在宿主负载干扰。这些观察不能证明稳定的厂商速度或流式优势。请求 stream 也可能接受 JSON 回退；不宣称实际响应媒体类型为 SSE。只能获得 CLI 合并后的规范化 token 总量，输入/输出/缓存分量保持 null。

localhost 保持 finish 未释放的对照验证：完成仍在等待时已经观察到首文本。负向对照拒绝缺失 DONE、格式错误的 JSON 和尝试修改，即使已经看到提前文本也不通过。清理是有界的尽力而为；生成代码验证是私有依赖隔离，不是操作系统安全沙箱。

## 关联文件仓库修复

```shell
python scripts/check_repository_fixtures.py
python scripts/check_repository_runner.py
python scripts/evaluate_repository.py --offline --variants paired --max-iterations 4 --timeout 60
python scripts/evaluate_repository.py --live --base-url https://codegoai.com/v1 --model gpt-6-sol --credential-env CODEGO_API_KEY --variants paired --repeat 1 --max-iterations 16 --timeout 600
```

这些生成的小型代码库将任务扩展到单文件预置历史回放之外：checkout 有九个生产文件/177 行，ingestion 有十个/174 行。每个任务要求修改四个关联文件；所有无关辅助文件必须保持逐字节一致。真实任务仅从公开源码和指令开始，不含预置诊断、参考补丁或隐藏检查。命令/MCP 保持关闭，独立读取最多四路并行，每次调用输出上限为 1800，请求上限为 60 秒。基线/压缩上下文估算预算为 48000/12000。

验证在新的目录中编译精确声明的 UTF-8 快照，使用空 classpath/sourcepath，并关闭注解处理，然后执行私有断言。任意位置出现额外 Java 文件、路径重定向或源码集合变更都会验收失败。已知修复 localhost 运行、编译器省略编辑/受保护文件对照建立验证器和工作流契约，与真实模型质量证据分开。

四次真实尝试全部 COMPLETED，并通过独立断言。checkout 两个变体均用八次调用；ingestion 均用九次。每次恰好修改四个必需文件，保留其他五个/六个辅助文件。checkout 源码哈希仅在两种等价收据渲染写法上不同；ingestion 最终源码相同。基线/压缩累计输入为 124143/123135，缓存输入为 96640/95360，输出为 2425/2254。新鲜短历史中的输入差约 0.81%，远小于预置历史的 72.17% 降低；这不能证明普遍节省。进程中位数为 63753.178/62698.022 ms。单次固定顺序的配对重复不能证明稳定提速或普遍质量。

全部十五个原始编译器对照符合预期；两个额外 `.harness` Java 源码对照也正确拒绝。因 Windows 拒绝创建符号链接，四个链接创建对照明确跳过。十个解析后端点对照和四个工作区对照通过。加固后的独立 localhost 修复套件四次运行全部通过。[v2 归档](benchmarks/codego-acceptance-v2.json)包含精确真实/离线报告、报告哈希、已审阅检查器身份、协议和限制。临时工作区清理后不保留模型工作区源码，仅保留源码哈希和独立检查结果。

早期 [v1 归档](benchmarks/codego-acceptance-v1.json)保留最初模型发现/回放失败及单独标记的后续检查。Linux 运行/CI 执行、真实终端信号、非 Chat 原生流式输出、Bedrock/Vertex 和多模态验收仍不在当前已验证支持范围内。最后一次模型调用后，真实凭据控制器已明确关闭。
