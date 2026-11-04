# 流式 HTTP 400 自动降级验收

日期：2026-10-21。

## 原因与行为

原先只有部分流内错误带有可降级标记，HTTP 400 在流式传输层直接抛出普通异常，因此没有进入 CLI 的自动重试策略。

现在原生 Chat Completions 流式请求收到 HTTP 400 时，保留状态码并标记为可尝试非流式。CLI 沿用已有策略：在允许降级、未输出回复文本且没有取消时，将同一轮请求改用非流式发送一次。移除流式参数，保留原模型、消息、工具和其他参数，不新增用户消息，不重放已执行的工具。当前进程后续请求使用非流式，可用 `/stream on` 重新开启。

HTTP 400 可能来自其他请求参数，本次改动不保证非流式请求一定成功。再次失败即停止并保留失败检查点；原本就是非流式的 HTTP 400 不重试。HTTP 401/403/404/413/422/429 不触发这项降级；`agent.stream-fallback: false` 和取消操作仍然有效。错误正文不写入日志或会话。

## 验证证据

- 修复前新增回归测试有 6 项 HTTP 400 场景失败，重现没有自动降级的问题。
- 修复后定向测试 `StreamFallbackTest` 22 项、`NativeChatStreamingTest` 15 项，37/37 通过。覆盖次数上限、取消、配置关闭、JSON 输出及用量记账、检查点和工具不重放。
- `mvn -B -o verify`：570 项测试，562 项通过、8 项平台条件跳过，0 失败、0 错误。日志位于 `harness-cli/target/http400-verify.log`。
- `python scripts/smoke_stream_fallback.py --result target/http400-fallback.json`：项目运行包 4/4 通过，覆盖流内错误与 HTTP 400 的交互和 JSON 模式。
- `python scripts/install.py` 更新本机全局安装并保留用户配置；同一脚本指定全局 `ylc.cmd` 后 4/4 通过，结果位于 `target/http400-installed-fallback.json`。

所有请求均发送到 localhost 夹具，未调用真实厂商。已经运行的旧 `ylc` 进程需要退出并重新启动，才会加载更新后的运行包。
