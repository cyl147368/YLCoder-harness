# 原生 Chat Completions 解析器集成

在 `OpenAiCompatibleProvider.Model.complete(request, observer)` 和新包私有 `OpenAiChatStream` 中实现原生 SSE。单参数同步路径不变。Azure 继承重载，OpenAI-compatible/Ollama 使用相同解析器。

请求保留编码后的模型/历史/工具，加入 `stream:true`，声明接受 SSE/JSON。仅原生 OpenAI 和 Azure 请求 `stream_options.include_usage`。请求头优先级仍通过 `setHeader` 使用配置覆盖。负责人负责的有界传输只交换一次，不重试。返回 JSON 的网关解码完整响应体，不合成文本回调。

解析器立即发出可见内容，同时按 index 保留函数碎片，直到受支持结束原因和 `[DONE]` 都到达。支持交错函数参数和仅用量尾块。完成调用要求连续 index、function 类型、非空白 ID/名称、唯一 ID 和严格 JSON 对象参数。重复 JSON 键、尾随 JSON、非法 index/type、旧函数调用、格式错误块和未知结束标记，以脱敏消息失败，不含原始 cause。拒绝或截断回合不暴露调用。正常 EOF 后缺失 DONE 或结束标记也失败。

除传输限制外，累积限制为128调用、1 MiB可见文本、4 MiB合计参数字符、每个ID/名称4 KiB。隔离观察者 RuntimeException。解析器诊断绝不包含原始厂商错误或载荷。

## 验证

`python harness-providers\target\native-parser-acceptance\verify.py` 使用 JDK 21 和现有 Surefire classpath，在私有 target 目录编译解析器、厂商集成、更新的 HTTP 基类及传输。**5项解析器测试通过**，覆盖交错并行调用、早期可见增量、用量尾块、DONE/finish要求、截断/过滤抑制、严格错误 JSON、非法碎片、输出上限及观察者失败隔离。

此次验证未运行 Maven、网络或付费调用。生成辅助脚本不是交付物。独立原生流式验收成员负责 localhost 传输和 CodeAgent 行为；负责人负责串行 reactor 和打包冒烟整合。

## 范围

适配器支持 choice index零，拒绝多 choice 流块，因为中立请求没有多 choice 契约。ID和函数名称从协议碎片组装，不虚构。加密推理元数据属于 Responses 厂商独立协议。对忽略中断的任意观察者代码，解析器不保证强制取消；传输调用者截止仍由负责人辅助类限制。
