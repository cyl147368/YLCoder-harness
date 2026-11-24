# 会话恢复验收交接

## 修改

`SessionStore.validate` 现于保存和加载时调用共享 `ToolHistory.validate(transcript, true)`。待执行和部分结果仍是有效检查点。重复调用、重复/孤立/不匹配结果，以及工具名不匹配，抛出固定 `IOException("Invalid session tool history")`，不附加可能暴露持久化证据的嵌套异常。校验在获取保存租约或创建/替换检查点前完成。

`SessionStoreTest` 增加两个情况：五种格式错误历史通过 save 和外部写入的有效 JSON 均被拒绝；保存失败保留现有字节，不留下临时文件。待执行和部分完成检查点原样往返。

`SessionRecoveryProcessTest` 启动独立 Java 子进程，使用本地模拟模型及真实 `CodeAgent`/`SessionStore` 检查点。工具写入修改标记和就绪屏障，持有会话租约并在返回工具结果前阻塞。父进程验证完整待执行检查点和恰好一次修改，强制终止子进程，等待退出，重新加载相同的已提交字节，重新获取释放的租约并恢复。模型看到效果不确定结果，修改工具不重放。完成检查点保留厂商/模型元数据及不确定结果。不涉及网络或付费模型调用。

子进程在受控进程中断边界被杀死，非断电边界。此测试不声称 fsync 持久性或 Windows 进程树隔离。

崩溃夹具现还返回非空不透明 `ToolCall.providerMetadata` 映射，含 Gemini thought 签名及带转义/换行字符的第二个不透明值。在初始待执行检查点、进程死亡后重新加载检查点、恢复模型请求，以及完成持久化转录中，断言精确映射相等。这些断言单独验证不透明工具元数据，区别于会话厂商/模型标签；本地恢复测试不声称真实厂商序列化已验证。

## 验证

此成员未启动 Maven。利用现有胖 JAR 和项目内 Surefire 报告依赖路径，以 Java 21 直接编译 `ToolHistory`、`SessionStore` 及两个 CLI 测试类成功。编译输出隔离于 `harness-cli/target/mcp-check`。负责人应使用新编译核心运行 `SessionStoreTest,SessionRecoveryProcessTest`，随后串行 reactor 验证。新进程测试使用测试 JVM 的 `java.class.path`，与现有 MCP 子进程夹具一致。

负责的源码修改：`SessionStore.java`、`SessionStoreTest.java`、新 `SessionRecoveryProcessTest.java`。核心源码/测试仍归负责人，未编辑。保留其他成员修改。

负责人最终验收：串行 `mvn -B verify` 于 2026-10-14 10:45:22 Asia/Shanghai 通过，包括全部 15 项 SessionStore 测试（两项平台跳过）、子进程死亡测试和八项核心恢复测试。重建产物的打包 CLI/resume 冒烟也通过。上方早期直接编译证据是成员交接，不是最终验收。
