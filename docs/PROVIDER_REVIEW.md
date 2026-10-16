# 厂商适配器评审

状态：厂商修复、LangChain4j 流式输出、Responses 验收和 Gemini 函数调用元数据后续工作已在本地验证。以下原始审计为历史记录；当前覆盖与验证记录在末尾。

最近续接：OpenAI/OpenAI-compatible/Ollama/Azure 适配器路径现在已实现并验证原生 Chat Completions 流式输出。下文“全部原生适配器同步”的说法是历史状态。当前协议上限和证据见[原生流式输出](NATIVE_STREAMING.md)。2026-10-14 12:00:05 Asia/Shanghai 的完整 reactor 通过 212 项测试、六项平台跳过；七个打包冒烟测试通过。此历史检查时真实厂商/Linux 验证仍待完成；后续 CodeGo 实测另见 CODEGO_EVALUATION.md。

## 从源码确认的问题

1. GeminiProvider.encode 使用 parseObject(message.content()) 序列化工具结果。普通工作区工具返回纯文本。任何不是 JSON 的 read/search/run 结果都会在第二次模型请求前失败。JSON 数组/标量被静默丢弃。应把非对象内容包装到 output/result 对象中。
2. AbstractHttpChatModel 携带 authorization 请求头和 x-api-key 时仍跟随重定向。应禁止自动重定向，或明确限制凭据和重定向为同源；用两个 localhost 服务测试。
3. OpenAI 和 Anthropic 解码将缺失响应包、内容过滤/拒答错误和错误 finish reason 当作 STOP。智能体可能把空/损坏厂商响应标记为成功。应增加严格响应验证并显式处理 FinishReason.CONTENT_FILTER。
4. 自定义 config.headers() 在 protocolHeaders() 后通过 builder.header 添加，因此 Authorization / x-api-key 覆盖会产生重复值。应按大小写不敏感方式合并键，或使用 setHeader。
5. ProviderConfig.toString() 原样包含 baseUri，URL 查询凭据和 userinfo 会泄露。应拒绝 userinfo，并拒绝 query 或安全脱敏端点格式。
6. 全部厂商错误保留原始异常为 cause；仅对顶层异常文本脱敏不能阻止密钥出现在堆栈中。
7. Anthropic 用量统计包含 input_tokens，但总输入省略 cache_read_input_tokens 和 cache_creation_input_tokens。它与 OpenAI/Gemini 总输入语义不同，会低报缓存输入消耗。
8. 核心 ToolCall/ChatMessage 不保留 Gemini thought signature/不透明厂商工具元数据。现代模型可能因缺失必需签名而拒绝后续工具响应。需要改核心 SPI，或如实声明受支持模型限制。
9. OpenAI 无条件发送 max_tokens；较新的推理模型要求 max_completion_tokens。应按端点支持/配置输出上限，不应静默承诺全部模型可用。
10. 声明了 LangChain4j 依赖，但没有适配器使用它，包括 Bedrock。当时注册表仅支持 openai、openai-compatible、ollama、anthropic、gemini、azure-openai；不能宣称原生 Bedrock。

## 构建观察

exec_command 使用 cmd.exe/login=false 可运行；PowerShell 启动因 CET 支持错误失败。Maven 路径：D:\software\apache-maven-3.9.14\bin\mvn.cmd。单模块测试需要 -am，因为 core 尚未安装。当时 reactor 厂商测试因并发中的 CodeAgent.java 引用不存在的 FinishReason.CONTENT_FILTER（第 82 行）而停止，似乎是负责人/核心成员正在进行的工作。

## 修复轮次完成

在 harness-providers 中修复问题 1–7 和 9，把 ProviderAdapterTest 从 6 项扩展为 16 项。增加 Anthropic 和 Gemini 的分组并行工具结果序列化。16 项测试在进程内 Surefire 运行器中全部通过：

    mvn -pl harness-providers -am test -Dtest=ProviderAdapterTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0

默认 forked Surefire 即使已编译测试类仍发生 ClassNotFoundException；进程内运行器成功。该成员没有修改根构建设置。问题 8 和 10 仍属于更广泛兼容性/构建架构工作。原生 OpenAI 和 Azure 现在使用 max_completion_tokens；通用兼容端点和 Ollama 保留 max_tokens。

## LangChain4j 实现完成

注册表现在包含 `langchain4j-openai`，使用检查过的 `OpenAiChatModel` 和 JDK HTTP API 实现真正的 LangChain4j 1.22.0 适配器。它映射 system/user/assistant/tool-result 消息、嵌套对象和数组 schema、工具调用、finish reason，以及输入/输出/缓存用量。禁止重定向，SDK 异常细节脱敏。厂商模块保留实际使用的 OpenAI 和 JDK 传输 LangChain4j 依赖；未使用的 Anthropic/Gemini/Azure/Bedrock 产物被移除。原生 HTTP 适配器仍可用。未实现原生 Bedrock 支持。

## Gemini 函数调用元数据完成

`ToolCall` 现在包含不可变 `Map<String,String> providerMetadata`，构造时复制。原有三参数构造器仍可用，并产生空 map。Jackson 可读取缺少 `providerMetadata` 的旧记录；缺失或 null map 转为空。序列化往返测试验证元数据不变。记录规范构造器改变，外部 SPI 扩展应重新编译。

原生 Gemini 适配器把返回的 `functionCall.id` 保留为核心调用 ID，并存入 `gemini.functionCallId` 元数据，以区分厂商 ID 和本地生成的关联 ID。函数调用 part 上的 `thoughtSignature` 原样保留到 `gemini.thoughtSignature`，并在对应函数调用 part 上回放。匹配的 `functionResponse` parts 包含返回的传输 ID。签名和传输 ID 缺失时绝不伪造；只生成内部关联 ID。

两轮 localhost 契约测试覆盖同一工具并行调用、不同传输 ID、只有一个调用带签名、精确签名回放、匹配结果 ID，以及缺失签名/ID。函数参数必须为 JSON 对象。缺失和未知 finish reason 会失败。标记 `thought: true` 的文本不进入可见助手文本。

最初仅支持函数的限制已由下文消息元数据续接覆盖。多模态和通用模型兼容性仍未验证/不受支持。

## 当时的验证

2026-10-11，针对性厂商验证在默认 forked Surefire 运行器中通过：

```text
mvn -B -pl harness-providers -am test -Dtest=ProviderAdapterTest,LangChain4jOpenAiProviderTest,GeminiMetadataTest -Dsurefire.failIfNoSpecifiedTests=false
```

27 项测试通过：16 项原生适配器、7 项 LangChain4j、4 项 Gemini 元数据。所有 HTTP 测试使用本地模拟服务，未调用真实厂商 API。核心和 CLI 持久化/集成验证由负责人协调。早期临时 forked 运行器失败已不能复现。

## 2026-10-14 历史最终验收

中断的流式实现留下孤立异常处理器和缺失的脱敏辅助方法，已修复，并且没有保留 SDK 异常 cause。LangChain4j 适配器的流式重载由七项本地 SSE 测试验证：完成前提前文本、分片函数参数、缓存用量、未结束流拒绝、观察者隔离、超时、HTTP 错误脱敏和中断；多个断言共用测试。当时原生适配器仍同步执行。打包流式冒烟测试还用真实 shaded jar 验证提前 stdout、最终答案不重复和可解析 JSON。

`openai-responses` 使用 `/responses`、`store:false`、`max_output_tokens` 和 `reasoning.encrypted_content`。七项本地契约测试验证两轮间有序的加密推理/消息/函数回放、并行调用/结果 ID、用量、null 错误、不完整/拒答响应、格式错误/不支持输出、HTTP 前篡改回放拒绝，以及厂商中立历史。原始输出数组保存在首个工具调用上，经过 ID/名称/参数验证后回放。不完整/拒答响应绝不暴露可执行调用。

Responses 支持仅限文本和本地函数工具。不支持内置工具、多模态输出、流式 Responses 和仅推理的最终答案。推理要求加密内容。最初最终轮次载体的限制已由下文消息元数据续接关闭。会话可能包含不透明推理数据，必须保持私密。

当时最终 `mvn -B verify` 使用默认 forked Surefire 通过：发现 30 项 core、41 项 provider、43 项 CLI；108 项通过、六项 Windows 能力跳过，无失败/错误。仅在 Windows 本地执行；Ubuntu/Windows CI 矩阵已配置，但未远程运行。未使用付费或真实厂商调用。

## 消息元数据续接，2026-10-14 11:05 Asia/Shanghai

`ChatMessage` 和 `ChatResponse` 现在携带不可变厂商元数据。既有构造器重载和旧会话缺失/null map 仍受支持；规范记录签名改变，外部 SPI 扩展必须重新编译。循环将响应元数据写入检查点，两种统计策略都包含它，压缩保留已留存且含回放证据的消息可见投影。

Responses 在工具轮次和最终轮次的消息上保存有序的受支持输出，兼容旧式首调用回退。Gemini 保存有序的受支持 parts，包括签名 thought/text 和交错函数；thought 文本保持隐藏。两者在 HTTP 前验证回放一致性，并拒绝存在歧义的回放 JSON。这验证结构回放，不验证加密真实性或全部厂商/模型组合。详见 [Responses 说明](RESPONSES_CONTINUITY.md)和 [Gemini 说明](GEMINI_CONTINUITY.md)。

组合 reactor：38 core + 57 provider + 85 CLI = 180 项发现、174 项通过、六项平台跳过、零失败/错误。五个实际 jar 冒烟脚本全部通过。新增 `smoke_message_metadata.py` 跨独立 CLI 进程发送四个 localhost 请求，检查精确最终轮次持久化/回放和 HTTP 前篡改拒绝。该历史检查时真实厂商和 Linux 执行仍未验证。
