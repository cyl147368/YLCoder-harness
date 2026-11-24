# 原生 Chat Completions 流式验收

`NativeChatStreamingTest` 针对本地 JDK HTTP 服务执行公开双参数模型重载。使用原生 `OpenAiCompatibleProvider`，包括 OpenAI、Azure 和 Ollama ID 的请求选项兼容性。未使用 LangChain4j 路径或外部模型流量。

覆盖：

- latch 暂缓 finish/DONE 时可见文本已到达，累积仅用量尾块。
- 两个并行调用交错参数碎片，仅在 DONE 后按调用顺序执行，由真实 CodeAgent 修改哨兵验证。
- 忽略 `stream` 的网关返回普通 JSON，不虚构增量；用量选项仅用于原生 OpenAI/Azure。
- 长度/过滤结果抑制工具调用；结束标记后、DONE 前 EOF 导致零代理效果。
- 缺失/未知结束标记、格式错误块/错误、缺失 DONE、非法 index/ID/参数、重复 ID、不支持的旧调用和非法用量，以脱敏错误失败。
- 拒绝过大 SSE 事件和 JSON 响应体；响应头后卡住的体遵守总超时；调用线程中断结束等待并保留中断状态。
- 重定向不访问目标；HTTP 错误脱敏；显式认证覆盖保持单值；观察者错误不破坏生成。
- 接受 SSE 多行 data、注释和 CRLF 帧。

测试用 latch 和有限等待验证顺序/中断，不断言吞吐收益。超时检查针对配置 350 ms 截止给予三秒外层余量，容纳宿主调度。这证明本地适配器行为，不证明厂商互通或生产延迟。

成员源码审阅完成。Maven 留给负责人串行集成；本交接不声称测试已通过。
