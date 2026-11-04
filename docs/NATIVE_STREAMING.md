# 原生 Chat Completions 与 Responses 流式输出

CLI 的 `openai`、`openai-compatible`、`ollama`、`azure-openai` 使用原生 Chat Completions 流式传输；`openai-responses` 使用 Responses SSE。`langchain4j-openai` 保留其流式适配器。库调用者可用 `ChatModel.complete(request, onTextDelta)` 选择增量交付，单参数方法等待完整响应。Anthropic 与 Gemini 当前仍同步返回。

## 协议选择与自定义厂商

自定义网关支持 Responses 时，可以保留 `provider: openai-compatible` 并增加 `api-format: responses`，或使用 `ylc --api-format responses`。`base-url` 填 API 根路径，例如 `https://your-provider.example/v1`；程序追加 `/responses`，不要重复填写端点路径。`api-format` 只接受 `chat` 或 `responses`，只适用于 `openai`、`openai-compatible`、`openai-responses`；省略时保留 provider 原有默认协议。

审核模型默认继承主模型格式，也可用 `permissions.reviewer.api-format` 单独覆盖。同一连接仅改格式会保留密钥和请求头，改变厂商或地址则继续隔离凭据。审核分类等待完整结构化响应，不把中途文本当成允许结果。

## Chat Completions

适配器组装 choice-zero 文本和按索引标识的并行函数片段。只有收到可识别 finish 标记和 `[DONE]` 后才返回工具调用。调用必须有 ID、函数名称/类型和严格对象参数；长度截断、过滤或拒答响应不含可执行调用。缺失完成标记、格式错误数据、不支持的旧式函数调用或无效索引会以脱敏错误失败。

原生 OpenAI/Azure 请求用量尾块；通用网关/Ollama 为兼容性省略该可选设置。厂商未报告的用量在 CLI 标为未知，不能把底层计数的零值当成免费。

## Responses

Responses SSE 增量交付文本，并组装函数调用与推理输出项；加密推理内容会保留在厂商元数据中，后续请求与工具结果一起回放。只有完整响应确定后才交给智能体执行工具，不执行尚未结束或失败流中的函数片段。流式和同步都使用 `/responses`，不会因请求方式变化丢失协议选择。

交互界面为完整 Markdown 排版会暂存本轮答案；底层收到增量不等于界面逐字展示。`run` 可增量交付文本，JSON 模式只输出最终 JSON，不混入流式文字。中断或流后续失败前已显示的文本不表示任务已经成功。

## 错误、恢复和限制

网关对流式请求返回普通 JSON 时按完整响应接受，不编造增量。适配器本身不自动重试；CLI 统一管理单轮最多 3 次、等待 1/2/4 秒的重试预算。HTTP 408/429/500/502/503/504 保持原协议与流式模式重试。可降级 HTTP 400、流内错误、断连或超时在尚无可见文本时切到非流式；Responses 仍访问同一 /responses，不切到 Chat。临时 HTTP 重试与兼容降级共用预算，避免嵌套放大请求；临时 HTTP 失败不关闭后续任务的流式输出。已经输出部分文本、用户取消及认证/权限等明确失败不继续重试，401/403 在降级中也立即停止。普通畸形分块、TLS/配置问题不启动降级。已完成工具不会重放；agent.stream-fallback: false 仅关闭兼容降级，--no-stream 或 /stream off 直接选择非流式。

传输异常按有界的异常类型链分类，显示固定中文原因和异常类型；不输出原始异常消息、URL、请求头或响应正文。可以区分连接拒绝、DNS 失败、网络中断、连接提前关闭、超时和 TLS/证书错误。总时限超出时显示配置的毫秒数及 `timeout-seconds` 提示。普通非流式传输也使用同一诊断格式，但不会自行增加重试循环。

传输接受含注释、多行 data、BOM 及 LF/CRLF/CR 换行的 UTF-8 SSE，并限制完整响应、事件、文本及工具参数大小。原生 Chat 的完整响应体上限为 8 MiB，每行/data 事件为 1 MiB，可见文本为 1 MiB，累计参数为 4 MiB，ID/名称字段为 4 KiB，调用数为 128。配置超时覆盖请求头和响应体。中断会取消工作线程并关闭响应体；清理不会无限等待不配合的观察者，回调应保持简短并配合中断。

## 验证范围

`NativeChatStreamingTest` 使用 localhost 服务覆盖提前文本、工具派发、JSON 回退、用量、请求头、重定向、错误、有界载荷、截止时间、中断和分帧。`scripts/smoke_native_streaming.py` 验证打包 JAR 的提前 stdout、最终文本去重、JSON 隔离、完成写入后的工具回放，以及不完整流不执行写入。

`TransportErrorsTest`、`NativeTransportFailureTest` 和 `StreamTransportFallbackTest` 覆盖真实 HTTP 正文截断、端口拒绝、超时、取消、错误脱敏、同请求降级及工具不重放。`scripts/smoke_transport_fallback.py` 验证实际 JAR 或 `--launcher` 指定的安装版：两种协议的首次恢复、第三次恢复、次数耗尽和部分文字不重试，并测量 1、2、4 秒退避和检查保存的会话。

Responses 回归覆盖文本增量、完整函数调用、加密推理回放、普通 JSON、失败与中断；协议配置回归检查 CLI/YAML、主模型和独立审核模型的路由。以上测试使用本地模拟厂商，不能代替具体账户或网关的线上兼容性验证，也不能据此声称生产延迟已经改善。整包和安装版验收以实际测试记录为准。

2026-10-27 的源码包与全局启动器验收均通过，Responses-only 场景未发出 Chat 请求，详见[Responses 与 Maven 验收](RESPONSES_MAVEN_VERIFICATION.md)。

官方参考：[Chat Completions create](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。2026-10-14 的参考记录为 HTTP 200；接口规定 `stream:true` 使用 SSE，并可在 `[DONE]` 前提供空 choices 的用量块，自定义服务可能仅实现其子集。

Responses 官方参考：[流式响应指南](https://developers.openai.com/api/docs/guides/streaming-responses)、[流事件参考](https://developers.openai.com/api/reference/resources/responses/streaming-events)、[函数调用指南](https://developers.openai.com/api/docs/guides/function-calling)。
