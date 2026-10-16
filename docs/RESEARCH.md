# 设计参考

以下官方项目资料用于指导设计，是参考资料，不代表功能等价或市场占有率排名。特定版本的实现兼容性由仓库契约测试确定。

| 参考 | 采用的思路 | 最初调研记录中的实现 |
|---|---|---|
| [LangChain4j](https://docs.langchain4j.dev/) | Java 厂商/模型抽象和有类型的工具 schema | LangChain4j 1.22 OpenAI 适配器、厂商中立核心 |
| [OpenAI 函数调用](https://platform.openai.com/docs/guides/function-calling) | 显式调用/结果和迭代编程工具 | 原生 Chat Completions 适配器和有界循环 |
| [OpenAI Responses](https://platform.openai.com/docs/api-reference/responses) | 无状态函数输出和加密推理回放 | 文本/函数适配器和两轮本地契约 |
| [Anthropic 工具使用](https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview) | tool-use 块和分组结果 | Messages 适配器和模拟契约测试 |
| [Gemini 函数调用](https://ai.google.dev/gemini-api/docs/function-calling) | 函数调用/结果和厂商元数据 | generateContent 适配器和保留调用签名 |
| [LangGraph 持久化](https://docs.langchain.com/oss/python/langgraph/persistence) | 可恢复执行的检查点状态 | 原子本地对话检查点和不确定效果恢复 |
| [Aider](https://aider.chat/docs/) | 局部代码编辑和紧凑仓库上下文 | 字面量搜索、有界读取和哈希保护编辑 |
| [Model Context Protocol](https://modelcontextprotocol.io/specification/) | 可扩展外部工具 | 最初仅调研/待办，尚无 MCP 传输；后续已实现的 stdio 子集见 MCP.md |

初始工程采用有针对性的本地搜索，避免提前转储整个仓库；使用稳定 schema 提供缓存机会，并限制结果大小。后续效率主张需要可比任务、厂商用量、质量检查和延迟测量；初始合成上下文估算仅是回归夹具。后续实测证据见 BENCHMARKS.md 和 CODEGO_EVALUATION.md。
