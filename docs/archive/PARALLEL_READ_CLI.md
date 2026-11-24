# 并行读取 CLI 交接

`--parallel-reads` 和可信/显式 YAML `agent.parallel-reads` 选择1至8个并发安全读取，默认1。CLI覆盖在run、resume、interactive路径传到 AgentRunOptions 第七字段。非法 YAML 和有效 CLI 值在构建厂商或启动 MCP 前失败。AgentRunOptions构建移到厂商构建前，让所有生效代理限制在启动前验证。

编码提示增加一句：鼓励已知输入的独立 read/search/list 同回合调用，同时保留依赖，避免猜测性修改批次。

仅 WorkspaceTools 的 list_files、search_files、read_file 声明核心能力。修改、命令、changes 和 MCP 均未标为安全。现有命令/MCP 权限行为不变。

三个读取实现在执行前及遍历/逐行边界检查中断，不清除中断标记。不吞掉中断遍历失败。文件系统读取自身仍取决于操作系统。专门能力测试验证已有中断的读取立即失败并保留标记。

测试覆盖 YAML 边界/默认/可信、非法 CLI/配置在非法厂商/MCP 启动前拒绝、help发现、CLI run/resume 并行覆盖，以及精确工作区能力分类和自定义工具默认 false。已有 CLI端到端模拟测试现以4运行、以8恢复。核心有界调度和顺序测试仍由另一成员负责。

此成员未运行 Maven 或 API 调用。修改后的生产/测试文件依据共享核心 API 源码和现有胖 JAR 直接 Java21编译通过，输出隔离于 target/mcp-check。负责人应按新核心契约运行 ConfigLoaderTest、HarnessCliTest、WorkspaceReadCapabilitiesTest，再串行 reactor。源码归属仅限 HarnessCli、HarnessConfig、ConfigLoader、其测试、WorkspaceTools 的三个安全读取能力/中断检查，以及专门 WorkspaceReadCapabilitiesTest。
