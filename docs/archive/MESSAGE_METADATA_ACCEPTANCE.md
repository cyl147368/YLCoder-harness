# 消息元数据验收交接

`SessionMessageMetadataTest` 覆盖旧持久化消息缺失/null 元数据、源码兼容构造、不可变防御映射副本，以及 SessionStore 新数据往返的精确 UTF-8 值相等。独立 JVM 执行真实 CodeAgent 检查点路径，最终文本响应带不透明消息证据。子进程退出后，父进程加载检查点、追加用户追问，再用模拟模型恢复。精确元数据传入模型并留在完成检查点；从不调用修改哨兵工具。会话厂商/模型标签与消息元数据分别检查。

`TokenizerInputTokenCounterTest` 新增两项覆盖 UTF16、cl100k、o200k 统计。大型必需消息元数据必须增加计数；预算足够时保持精确；放不下时在厂商请求前失败。带元数据的助手可见内容也必须失败而非缩短，保护回放投影完整性。

此成员只负责/修改新 CLI 会话测试、现有 CLI tokenizer 新增测试及此交接文档。未改生产/核心/厂商源码，未运行 Maven，未调用网络/模型 API。实现使用负责人共享的 ChatMessage/ChatResponse 元数据 SPI。负责人应串行运行 `SessionMessageMetadataTest,TokenizerInputTokenCounterTest`，随后 reactor。此交接时共享 SPI 仍在实现，因此编译/测试执行尚待完成。
