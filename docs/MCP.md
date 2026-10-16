# MCP stdio 工具

CLI 可以启动显式配置的 Model Context Protocol 服务，并将其工具提供给编程智能体。客户端在进程 stdin/stdout 上使用按换行分隔的 JSON-RPC 2.0。请求协议 `2025-11-25`，并接受协商后的 `2025-06-18`、`2025-03-26` 或 `2024-11-05` 的受支持工具子集。它执行 `initialize`、`notifications/initialized`、分页 `tools/list` 和 `tools/call`。服务必须声明 tools 能力。

参考：[stdio 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)和[工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)。

## 配置与权限

将以下配置放入显式选择的 `--config` 文件、用户配置（`~/.code-harness/config.yaml`），或通过 `--trust-project-config` 选择的项目配置：

```yaml
mcp:
  max-message-bytes: 1048576
  max-schema-bytes: 65536
  max-tools: 128
  max-pages: 16
  servers:
    source-index:
      command: java
      args: ["-jar", "/absolute/path/to/your-mcp-server.jar"]
      timeout-seconds: 30
      environment-env:
        SERVICE_TOKEN: MY_SERVICE_TOKEN
permissions:
  allow-mcp-tools: false
```

`command` 是可执行文件，`args` 是直接传给 `ProcessBuilder` 的参数列表，不会隐式使用 shell。进程在选定工作区运行并继承宿主环境；`environment-env` 将子进程环境名称映射到宿主环境变量名称。不要把凭据放入可执行文件参数或受版本控制的配置。信任配置即授权启动其中的可执行文件，因此选择前请审阅。

外部工具执行具有独立权限。交互调用逐次提示；非交互调用默认拒绝，除非 `--allow-mcp-tools` 或可信的 `permissions.allow-mcp-tools: true` 授权。`--allow-commands` 只授予 shell 权限，不授予 MCP 权限。例如：

```shell
ylc --config /absolute/path/to/config.yaml --allow-mcp-tools run "使用源码索引查找解析器"
```

默认启用的 `permissions.auto-review` 只用于有限的本地只读命令，不审核或自动批准 MCP 调用。即使工具名称包含“读取”或“搜索”，外部服务仍可能有副作用，不能继承 shell 的审核结果。`--no-auto-review` 也不改变 MCP 审批策略。

仓库配置默认被忽略，包括 MCP 服务和权限字段。`resume` 时重新加载配置和 MCP 授权；会话文件不保存启动授权。

## 行为与限制

工具名称为确定性、有命名空间、厂商安全的标识符，最多 64 字符；带有可读的服务/工具前缀，并用哈希区分清理后相似的原名称。输入 JSON schema 保留原结构。服务和发现的工具顺序稳定。模型调用前，发现过程会拒绝重复名称、无效对象 schema、过量工具/schema、重复游标和分页溢出。

文本内容和嵌入文本资源转换为工具输出。`structuredContent` 以 JSON 追加。`isError` 结果转换为工具失败。二进制图片/音频/资源内容和资源链接替换为明确省略标记，不发送给纯文本模型适配器。工具输出裁剪到配置的 `agent.max-tool-output-chars`。帧、schema、工具数和分页均有界。stderr 被排空但不记录；JSON-RPC 连接错误不会回显服务诊断或原因。

每个请求都有超时，包括因进程 stdin 背压而阻塞的写入。超时、格式错误/过大帧、连接失败或中断会直接终止配置的服务，并使请求失败。交互退出、`run`、`resume` 和启动失败后，CLI 都会关闭所有服务。原生子孙进程发现和管道关闭异步执行，不能延迟请求超时；子孙进程清理是尽力而为，直接终止可能在发现完成前使子进程成为孤儿。这些外部进程具有当前用户的宿主权限。工作区文件工具的包含关系检查不会将 MCP 服务沙箱化。

本版本只提供静态发现快照和 stdio 传输。尚未实现 HTTP/SSE 传输、roots、sampling、elicitation、MCP resources/prompts 发现、工具列表变更刷新、订阅和长时间 MCP task API。不支持的服务端请求收到 JSON-RPC `-32601`；通知被忽略。服务若把 stdout 用于日志而非 JSON-RPC，会被拒绝。

## 本地验证

`McpToolsTest` 启动 Java 夹具子进程，覆盖握手/分页、原始 schema/名称、文本和结构化结果、错误结果、输出上限、权限拒绝、格式错误/过大/错误帧、发现限制、超时、中断和关闭。`McpCliTest` 通过本地厂商模拟服务驱动 `run`、`resume` 和交互退出，确认 MCP/shell 独立权限和子进程清理。`ConfigLoaderTest` 覆盖可信选择和无效 MCP 配置。这些测试不启动外部服务，也不调用付费模型。
