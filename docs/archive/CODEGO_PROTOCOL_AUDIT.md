# 自定义网关协议审计

此审计覆盖当前 `openai-compatible` 适配器、原生Chat Completions流解析器及CLI配置，用于负责人明确授权的CodeGo真实验收。没有读取凭据或发起网络/模型请求。

## 建议首个真实探测

从同步路径开始，独立于SSE确认模型、端点、认证：

```text
harness --config <trusted-config.yaml> --workspace <workspace> \
  --provider openai-compatible --model <deployed-model> \
  --base-url https://codegoai.com/v1 --api-key-env <ENV_NAME> \
  --no-stream --json run "简短回复健康检查，不调用工具。"
```

适配器删除base URL尾斜杠后追加 `/chat/completions`。因此 `https://codegoai.com` 指向 `/chat/completions`，常见OpenAI兼容部署需要 `/v1/chat/completions`；运行前确认网关文档基础路径。此厂商的ProviderConfig发送 `Authorization: Bearer <key>`。配置header-env随后通过setHeader应用，可用显式自定义Authorization替换bearer；不能记录此覆盖值。

openai-compatible将输出上限编码为max_tokens；OpenAI/Azure别名用max_completion_tokens，文档要求通用字段的网关不要用这些别名。CLI默认输出3,000token（agent.max-output-tokens），低成本冒烟设小正值。省略YAML键保留默认，显式null才省略协议上限。CLI当前无直接--max-output-tokens，使用可信显式配置。--no-stream仍发送相同历史/工具schema，不合成增量。要求无工具的提示不会删除普通CLI请求中的工具定义；真正无schema协议探测需要负责人直接最小请求。

## 原生流式后续

JSON探测成功后，仅当网关返回标准 `Content-Type: text/event-stream` 才去掉--no-stream。请求包含stream:true；stream_options.include_usage:true仅为原生openai/azure-openai发送，通用CodeGo不发送。传输也接受忽略流式而返回application/json的网关，解码响应体但不合成文本增量。

SSE网关须分隔事件、提供choice index0、以识别的文本finish_reason（stop、tool_calls、length、content_filter）结束，并发送[DONE]。解析器到达时发delta.content可见碎片，但到finish和DONE验证前不暴露工具调用。碎片并行函数要求从零连续index、function类型、组装后的非空ID/名称、严格JSON对象参数。ID/名称重复时按碎片拼接，因此每碎片重复完整ID/名称的网关与支持格式不同。接受choices为空的仅用量块。缺失DONE、缺失/未知finish、格式错误参数、拒绝和截断永不授权工具执行。

这些要求是有意安全边界。JSON省略finish_reason的网关会以脱敏厂商错误失败，不会误执行修改。旧function_call碎片被原生解析器拒绝；若非流响应使用支持的tool_calls，可用--no-stream，否则在网关配置输出当前Chat Completions格式前视为不兼容。

## 真实结果应记录内容

记录厂商/模型/基础路径（无API key）、请求模式、退出状态、结束行为、观察到的JSON/SSE媒体类型、只读工具行为。单CLI结果JSON不暴露实际响应媒体类型。用量可选：通用流式不请求用量扩展，没有计数到达时可合法以本地全零用量完成。CLI合计不区分缺失和报告零。同步解码缺失记零，并通过Jackson asLong(0)转换数值；流解析器要求非负整数。仅将实际观察网关计数视为报告用量，不从缺失值推断账单或零成本。单独记录本地token估算。

原生传输限制总响应头/体截止、总大小8MiB、事件/行1MiB。旧同步路径用普通HttpClient缓冲，缺少这些显式总响应体上限。--no-stream验收保留子进程超时；切换模式是协议诊断，不代表超时/内存行为等价。

真实网关返回错误时，只保留厂商脱敏消息和HTTP状态。不要贴响应体、认证头、含密钥URL或模型工具参数。不自动重试付费请求。

## 验收顺序与范围

1. --no-stream，要求纯文本、无工具执行（CLI仍声明schema）。
2. --no-stream，一次无害只读工具，再最终文本；验证第二请求带结果。
3. 原生流式纯文本，检查早期增量和最终状态。
4. 原生流式只读工具，检查finish + DONE前不执行工具。

最后两项仅在网关声明SSE及当前工具字段时有意义。本审计不声称CodeGo模型质量、厂商账单准确或上述协议行为之外的支持。
