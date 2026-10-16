# OpenAI Responses 消息连续性

## 已实现行为

完成的 Responses 文本与函数工具回合现在通过 `ChatResponse.providerMetadata["openai.responses.outputItems"]` 携带有序输出数组。更新后的核心 SPI 将映射持久化到助手消息。因此最终回答为后续用户追问保留加密推理，包括代理检查点/恢复路径。

回放保留受支持的 reasoning、assistant-message、function-call 项、顺序、ID、消息 phase、annotations 和其他不透明字段。推理不显示在可见文本中。只有推理或可见文本为空白的最终输出不能完成编码任务。已完成、可回放回合的 reasoning 项必须含加密内容。未完成和拒绝响应既不返回可执行调用，也不返回可回放元数据。

适配器在 HTTP 前，对照可见助手文本及全部预期函数 ID、名称、JSON 参数对象，校验原始回放投影。拒绝错误角色元数据、格式错误 JSON、重复对象键、尾随 JSON 值、重复输出 ID、后续调用重复载体、投影变化和冲突双副本。原始回放与预期中立调用的函数参数均用严格解析。对象键顺序或空白不同，但等价的 JSON 参数对象可以接受。

输出元数据存放在第一个 ToolCall 上的旧会话仍可回放。消息级和旧副本同时存在时，两者都必须有效且描述同一输出数组；数组只发送一次。新解码只使用消息级载体，避免存储两个副本。

不透明值被保留，本地不会独立认证。投影和副本验证检测历史不一致；加密推理认证仍由厂商端负责。

## 证据

使用 JDK 21 运行 `python harness-providers\target\responses-continuity-acceptance\verify.py`，依据现有 Surefire 依赖 classpath，在私有 target 目录编译当前核心源码、编辑后的 Responses 厂商和 JUnit 测试。结果：**13 项测试通过**。生成辅助脚本不是交付物。

覆盖：

- 新消息载体下有序加密推理和并行调用。
- 最终推理/文本回答随后经过真实 CodeAgent 检查点/恢复及用户追问，保留 phase 和不透明 annotations。
- 旧首调用回退与匹配双副本只回放一次。
- 可见文本/调用变化、格式错误和歧义 JSON、缺失加密推理、重复 ID、冲突副本，在再次 HTTP 请求前拒绝。
- 已有用量、未完成/拒绝、不支持输出及厂商中立历史契约。

所有请求均为 localhost。未运行 Maven 或真实/付费模型调用。负责人必须串行运行 reactor 完成组合验证。

## 修改的交付文件

- `harness-providers/src/main/java/dev/codeharness/providers/OpenAiResponsesProvider.java`
- `harness-providers/src/test/java/dev/codeharness/providers/OpenAiResponsesProviderTest.java`
- 本文档。

负责人提供参考：<https://developers.openai.com/api/docs/guides/reasoning>（2026-10-14 获取，HTTP 200）。无状态推理要求历史中保留有序响应输出。已有 `store:false` 和受支持加密推理 include 保持不变。
