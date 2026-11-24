# 实际 CLI 首段文本测量

负责人：cli_acceptance。仅负责新建 scripts/measure_latency.py、scripts/check_latency_measurement.py 和 docs/LATENCY_MEASUREMENT_REVIEW.md。其他成员也在工作；不要修改评估器、验证器、打包器、Java 或 WORKPLAN，也不要回退其他人的工作。负责人掌管凭据和每次真实网关调用。不要访问真实网络、凭据或控制器，也不要运行 Maven。

实现一个有时间上限、必须显式启用的 Python 运行器，从实际 Java 胖 JAR CLI 测量标准输出第一个非空白字节及进程完成时间。使用子进程单调时钟和独立、有限的标准输出/标准错误读取器；不要测量模拟策略的时间戳，也不要假定 stream:true 就代表实际 SSE。临时、显式信任的配置限定一次迭代，只请求 LATENCY_PROBE_OK，不用工具，max-output-tokens 为 64，shell/MCP 权限为 false。如果模型返回工具，一次迭代会阻止执行并使检查失败。读取最终检查点，要求 COMPLETED、唯一且精确的模型回答 LATENCY_PROBE_OK、零工具、无意外工作区文件；不打印原始模型或标准错误输出。报告请求的流式/同步模式、首个可见文本与进程时间（ms）、归一化用量及可用性、哈希、状态/检查、脱敏的代理错误类别。缺失首字节代表失败，不能记为零延迟。外部运行必须显式提供 --live、--base-url HTTPS、--model 和 --credential-env。支持交替流式/同步顺序、--repeat 1..5（默认 3），每个子进程最多 90 秒。凭据值只留在子进程环境中，并在编码前递归脱敏；不能把密钥值写入配置。绝不自动重试失败样本。

使用 localhost 服务和真实 JAR 提供离线验收：完成前暂缓结束事件的早期 SSE 文本、同步 JSON、格式错误/缺失完成信号（不得误判通过），以及工具调用样本（不得执行）。离线夹具只使用人工凭据和回环 URL，未授权外部访问。在审阅文档中提供精确证据。负责人审阅后执行真实样本，初始可能选择重复 2 次。端点支持保持通用，不限定 CodeGo。使用 cmd.exe/login:false。
