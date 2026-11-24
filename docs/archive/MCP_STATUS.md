# MCP 成员交接

实现已就绪，等待负责人串行验证。此成员未启动 Maven。全部修改生产文件依据现有胖 JAR 直接 Java21 `javac` 编译通过；输出隔离于 `harness-cli/target/mcp-check`。

- 新 `cli/mcp/StdioMcpClient` 实现有界换行 JSON-RPC、受支持协议握手、异步限时写入/请求、脱敏错误、通知/不支持服务请求处理、stderr排空、中断和进程清理。
- 新可关闭 `cli/mcp/McpTools` 发现分页工具、保留对象 schema、注册确定安全名称、分别审批外部调用、格式化文本/结构化/错误结果，并截短输出。
- `HarnessConfig`、`ConfigLoader`、`HarnessCli` 增加可信 MCP 配置、独立 `--allow-mcp-tools` 权限，以及 interactive/run/resume 的服务生命周期清理。
- 新增 `McpToolsTest`（七项，含子进程夹具和阻塞原生清理）、`McpCliTest`（两项 CLI/模拟集成），以及两项 MCP 配置测试。未调用真实模型或外部服务。
- 用户文档见 `docs/MCP.md`。

早期串行 Surefire 报告显示 `McpToolsTest` 6/6、`McpCliTest` 2/2、`ConfigLoaderTest` 6/6通过。后续 reactor 在已有子进程超时测试复现 Windows 原生后代发现卡住。最新修复将发现和可能阻塞的管道关闭移出请求/close路径，及时终止直接进程，并增加故意卡住发现的回归。未提高测试截止时间。此修复等待负责人串行测试；生产类依据已有胖 JAR 直接 Java21编译通过。证据及尽力进程树清理限制见 `docs/RECOVERY_FOLLOWUP.md`。

沟通说明：新任务和消息载荷仍是不可读加密 `gAAAA...` 字符串。负责人在 `CLI_ACCEPTANCE_TASK.md` 提供可读后续指令。所分配超时修复和只读恢复审计已完成；源码发现及建议恢复验收见 `docs/RECOVERY_FOLLOWUP.md`。

未改核心/厂商/WorkspaceTools/README/WORKPLAN/基准文件。旧沟通文件 `CLI_ACCEPTANCE_REQUEST.md`、`CLI_ACCEPTANCE_TASK.md` 可由负责人整合后删除。
