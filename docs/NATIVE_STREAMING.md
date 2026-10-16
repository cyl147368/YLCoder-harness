# 原生 Chat Completions 流式输出

除了已有 LangChain4j 适配器，CLI 现在也通过 `openai`、`openai-compatible`、`ollama` 和 `azure-openai` 流式输出。库调用者可用 `ChatModel.complete(request, onTextDelta)` 选择增量交付；单参数方法仍同步执行。不会改变已配置模型。

自定义网关拒绝流式请求时，在 interactive/run/resume 中使用 `--no-stream`。服务对流式请求返回普通 JSON 时，按一个完整响应接受，不合成增量。错误和中断流不会自动重试。原生 OpenAI/Azure 请求用量尾块；通用网关/Ollama 为兼容性省略该可选设置。现有用量 SPI 中缺失计数仍显示为零，不能据此认为免费。

适配器组装 choice-zero 文本和按索引标识的并行函数片段。只有收到可识别 finish 标记和 `[DONE]` 后才返回工具调用。调用必须有 ID、函数名称/类型和严格对象参数。长度截断/过滤/拒答响应不含可执行调用。缺失完成、格式错误数据、不支持的旧式函数调用或无效索引会以脱敏错误失败。流后续失败之前已经输出的可见文本可能已到达终端；这不是任务成功证据。JSON 输出从不包含增量文本。

传输接受含注释、多行 data、BOM 及 LF/CRLF/CR 换行的 UTF-8 SSE。完整响应体上限为 8 MiB；每行/data 事件为 1 MiB；可见文本为 1 MiB；累计参数为 4 MiB；ID/名称字段为 4 KiB；调用数为 128。配置的超时覆盖请求头和响应体。调用者中断会取消工作线程并关闭响应体；清理不会无限等待不配合的观察者。自定义观察者若忽略中断，可能在调用结束后仍存活，因此回调应简短并配合中断。

`NativeChatStreamingTest` 使用 localhost 服务覆盖提前文本、工具派发、JSON 回退、用量、请求头、重定向、错误/有界载荷、截止时间、取消和分帧。`scripts/smoke_native_streaming.py` 验证 shaded jar：提前 stdout、最终文本去重、JSON 隔离、一次已完成写入及结果回放、缺失 DONE 后无写入的恢复。这证明本地协议行为，不证明账户/模型可用性或生产延迟。Responses、Anthropic 和 Gemini 适配器仍同步执行。

官方参考：[Chat Completions create](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)，2026-10-14 获取时 HTTP 200。参考规定 `stream:true` 使用 SSE，并可在 `[DONE]` 前提供空 choices 的用量块。实现遵循此传输契约；自定义服务可能仅实现其子集。
