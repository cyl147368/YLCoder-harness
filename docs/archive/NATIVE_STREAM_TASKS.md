# 原生 Chat Completions 流式

负责人更新：NativeStreamTransport 和受保护的继承 client 已实现。exchange 对 SSE 返回 null，对完整 JSON 返回字符串；即使正常 EOF，调用解析器也必须显式验证 DONE。SSE 支持 LF/CRLF/CR、BOM、注释和多行 data；总字节上限 8 MiB，每行/事件上限 1 MiB。predicate 返回 false 时停止；未完成的尾事件失败。错误脱敏且不重试。回调可能在工作线程运行；不要为 JSON 回退合成增量。

2026-10-14 抓取官方参考，HTTP200：https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create 。`stream:true` 使用 SSE；`stream_options.include_usage` 在 `[DONE]` 前提供 choices 为空的用量块。更广泛的流式指南现主要描述 Responses，当前适配器依据此 Chat 参考。不要改变配置模型选择。

此为当时权威指派；原英文要求现按最新用户指令中文化，仅限项目，不真实调用/运行 Maven。其他成员也在工作，保留其他修改。负责人负责传输辅助类、整合和构建。此前已完成指派属于历史。

## 共享集成契约

负责人增加包私有 `NativeStreamTransport`，静态方法 `String exchange(HttpClient client, HttpRequest request, java.time.Duration timeout, java.util.function.Predicate<String> onEvent)`。text/event-stream 分发组装后的 SSE data 事件字符串；predicate=false 表示正常停止（例如 [DONE]）；返回 null。application/json 或缺失 content-type 返回有界完整响应字符串，以兼容忽略流式请求的网关。不支持的媒体类型失败。总截止时间包括响应头/体、字节/事件限制、中断/取消清理，不重试。错误必须使用脱敏 ProviderException 文本，不含原始 cause。传输回调在虚拟工作线程运行，模型调用等待。负责人将 AbstractHttpChatModel.client 改为 protected。现有 final complete(request) 保持同步，仅双参数重载流式。

## responses_acceptance 实现

负责 OpenAiCompatibleProvider.java、新包私有 `OpenAiChatStream.java` 解析器及可选解析器测试；交接 docs/NATIVE_STREAM_PARSER.md。重载 Model.complete(request, Consumer<String>)，编码相同请求体并加入 stream:true；仅对原生 openai/azure-openai 加入 stream_options.include_usage:true（通用/ollama 网关经常拒绝）。使用继承 client、通过 setHeader 设置请求头、Accept SSE/JSON 及上述传输。JSON 回退解码但不合成增量。不重试，失败脱敏。解析器累积 choice0 可见文本，按 index 组装碎片化并行工具调用，验证 index/type/name/ID 唯一性、参数严格 JSON 对象、结束状态和 [DONE]。未知/缺失结束状态、缺失 DONE/未完成 EOF、格式错误/拒绝/截断调用永不执行。支持尾部仅用量块，统计非负数值用量/缓存。拒绝不支持的旧 function_call 和格式错误块。工具数量 <=128，累积文本/参数有界（传输总 8 MiB 已有限制）。隔离观察者 RuntimeException。完成文本/工具通过 ChatResponse 归一化，文本到达后在结束前发出。除必要情况外保持同步行为，不改其他厂商。错误不回显原始响应/回放密钥。旧 complete(request) 调用保持行为。不运行 Maven。

## gemini_continuity 验收

仅负责新 `NativeChatStreamingTest.java` 和 docs/NATIVE_STREAM_ACCEPTANCE.md。通过 localhost HTTP SSE 测试真实原生 openai-compatible 双参数重载：服务放行结束前可见文本；并行碎片调用、尾用量和完成前不执行（CodeAgent 哨兵）；忽略流式的 JSON 回退；截断/过滤抑制调用；格式错误/缺失 DONE/结束状态/错误；有界响应体/事件；响应头后卡住的超时和调用线程中断及时终止；不跟随 HTTP 重定向、认证头覆盖/脱敏、观察者错误隔离。使用小型确定夹具、latch 和合理有限超时。负责人创建传输，responses 成员负责解析器。不改生产代码或运行 Maven。实现落地时可检查协议。

## cli_acceptance 打包冒烟

仅负责新 scripts/smoke_native_streaming.py 和 docs/NATIVE_STREAM_CLI.md。沿用已有流式冒烟结构，但使用原生 openai-compatible CLI，证明暂缓结束前有实际 stdout 且只有一次最终输出；JSON 模式仍可解析；一个已完成流式工具调用后接最终文本，只修改一次且第二请求带结果；未完成流即使有看似完整修改调用，也不执行且检查点仍可恢复。仅 localhost，超时后清理。不改生产代码或运行 Maven。负责人增加 CI 项。
