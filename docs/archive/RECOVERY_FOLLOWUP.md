# 恢复后续证据

## MCP 夹具就绪发布修复

最终 reactor 暴露测试夹具发布竞态：中断测试在文件创建与 `Files.writeString` 完成之间观察到 `pid.txt` 存在，取消子进程，随后解析了空 PID。夹具现先写完整唯一临时 PID 文件，再在相同目录原子替换 PID 路径。因此观察最终路径即是内容完整的就绪屏障。`config` 每次启动前删除旧 PID；中断分支再次使用相同配置前，也保留显式删除。这样也阻止早前已停止进程满足后续启动就绪检查。

现有超时和直接服务终止断言不变，未增加截止时间，也未为夹具修复改变生产源码。trace 检查在完整 JSON-RPC 请求/响应处理后执行；中断夹具先发布 PID 再阻塞，因此其余就绪假设不会解析并发创建的空文件。未启动 Maven，负责人继续串行验证。

## MCP 超时清理修复

串行 Surefire 报告复现 `McpToolsTest.timeoutAndInterruptionStopTheSubprocess` 的生产截止失败：MCP 请求已经达到一秒超时，外层五秒边界仍超时。调用栈为 `StdioMcpClient.close` -> `Process.descendants` -> `ProcessHandleImpl.getProcessPids0`。直接终止在后代枚举之后，原生调用卡住导致服务存活、工作区被锁。

`StdioMcpClient.stopProcess` 现将尽力的后代枚举调度到守护平台线程，同时对配置的直接进程调用 `destroyForcibly`，不等待发现。管道关闭也放独立守护线程，因为可能等待被阻塞的 stdin-write 监视器。请求/close 不 join 原生树发现或管道清理。枚举最多考虑 1,024 个后代句柄。进程树清理仍是尽力：直接进程终止可能在发现完成前留下孤立后代，原生发现自身也可能一直卡住。这不是操作系统进程沙箱；保证 Windows 树终止需要 job-object 实现，超出此次修复。

未提高测试截止时间。新增 `directTerminationDoesNotWaitForStalledNativeDescendantDiscovery`：提供后代枚举在 latch 阻塞的进程，验证清理在 500 ms 内返回，发现仍阻塞时直接终止进程，然后释放守护线程清理。已有夹具超时/中断/CLI 生命周期测试继续验证真实直接服务终止。

此成员未启动 Maven。负责人应先串行重跑 `McpToolsTest,McpCliTest`，再运行 reactor。生产源码只改 `cli/mcp/StdioMcpClient.java`；测试源码只改 `cli/mcp/McpToolsTest.java`。

## 检查点只读审计

下方历史发现已在续接中实现：核心 `ToolHistory` 在检查点/派发前校验厂商调用 ID，在修复前校验恢复证据；`SessionStore` 校验保存/加载历史，允许待执行结果。已增加真实进程死亡测试，见 [RECOVERY_ACCEPTANCE.md](RECOVERY_ACCEPTANCE.md)。最终串行 reactor 验收于 2026-10-14 10:45:22 Asia/Shanghai 通过，包括全部七项 MCP 测试和两项 MCP CLI 测试。重建 JAR 的打包 MCP 冒烟通过。下面描述修复前行为。

检查点路径正确地在工具效果前保存助手调用记录，检查点失败时停止派发，保存各个完成结果，恢复时补入效果不确定结果而非自动重放调用。原子替换、大小限制、遗弃临时文件隔离和锁均有专门测试。文档正确说明不保证 fsync/断电持久性。

审计时，正确性缺口允许厂商响应中重复调用 ID。`ChatResponse`、`ChatMessage` 接受单助手回合重复 ID。修复前 `CodeAgent` 将响应写为检查点并执行每个请求调用，直到下次压缩才验证配对。响应中两个 `ToolCall("same", "edit", "{}")` 可两次调用修改工具，下一迭代才因不匹配/重复调用结果失败，同时持久化无法安全配对的检查点。新增派发前校验及回归测试关闭此路径。

建议复现/测试：本地 `ChatModel` 第一回合返回这两个调用，`edit` 工具递增 `AtomicInteger`，捕获检查点。审计时执行次数在第二迭代失败前到两次。预期安全行为是 `FAILED`、零工具执行、不写格式错误助手调用检查点，并提供描述重复 ID 的错误。应在检查点或派发前校验响应调用集合。本审计未改核心源码。

审计还发现恢复修复静默丢证据：`repairInterruptedTools` 用 `results.put(id, message)` 索引连续工具结果，覆盖重复 ID；仅输出助手调用中存在的 ID，丢弃不匹配结果。修复前 `SessionStore` 验证检查点身份/元数据，但不拒绝错误调用/结果关系。已加载转录若一个助手调用后有两个同 ID 结果，恢复可只使用最后结果，并在请求厂商前覆盖检查点。共享历史校验现于修复/保存/加载前拒绝此证据；回归验证没有模型/检查点/工具效果且原检查点字节不变。真正缺失结果仍获得效果不确定占位。

审计发现缺失真实崩溃边界集成测试。新增子运行器保存待执行调用、修改标记、发出屏障并阻塞；父进程终止它、加载检查点，以模拟模型恢复。通过断言验证一个副作用、不重放工具、未知效果结果、精确不透明工具元数据保留、锁释放和完整持久化 JSON。这验证进程死亡恢复，不证明断电持久性。
