# 预置回放失败追踪

只读审阅评估器和当前Java源码。未访问控制器/凭据、网络调用、改源码或运行Maven。负责人报告小型真实工作流通过，而预置配对返回零迭代；这里未查看原始真实响应。

## 零迭代证明什么

`CodeAgent.java:60` 初始化iterations为零，`CodeAgent.java:85` 仅在 `model.complete` 返回非null响应**之后**递增。零表示没有被接受的归一化首响应，不能证明零HTTP请求、零厂商生成或零费用。已完成厂商响应被本地解析器拒绝仍报零。用量同样在此点之后累积，因此零用量不能证明上游未消耗。

若运行器解析到CLI JSON对象 `status:FAILED`、`iterations:0`，通常prepare及会话加载已成功进入代理；此处的早期失败会走顶层执行处理器，产生stderr且无最终结果JSON。应区别NO_RESULT和进程TIMEOUT。

## 首次递增前的失败

| 阶段 | 精确源码路径 | 可安全区分的证据 |
| --- | --- | --- |
| 历史修复 | `CodeAgent.resume` → `repairInterruptedTools` → `ToolHistory.validate` | 脱敏孤立/不匹配/重复历史错误；没有接受厂商响应 |
| 初始持久保存 | `CodeAgent.java:66`，`HarnessCli.execute`监听器 | `Cannot save session checkpoint` 或会话验证错误 |
| schema统计/压缩 | `CodeAgent.java:67`–75；`ContextCompactor.compact` | schema超预算、不匹配配对或必需上下文估算超预算 |
| 取消 | 请求前和请求回调后检查 | CANCELLED、零迭代；无首个归一化响应 |
| 请求构建 | `OpenAiCompatibleProvider.Model.complete`的exchange前，或同步基类client.send前 | 脱敏请求失败；可能端点/请求头/序列化格式错误 |
| 传输 | `NativeStreamTransport.exchange`；`AbstractHttpChatModel.java:48` | 脱敏HTTP状态、超时、中断、不支持媒体类型、大小上限消息 |
| 响应验证 | 原生SSE解析器、JSON回退`OpenAiChatStream.parse` → `decode`；同步基类JSON解析 → `decode` | 缺失/未知结束状态、非法信封、格式错误参数/index、缺失DONE、非法用量、重复JSON键或尾随JSON |

工具执行失败和 `ToolHistory.validateCalls(response.toolCalls())` 在递增**之后**发生，因此不能解释普通FAILED/零记录。必需源码编辑成功/独立检查失败也更晚，不能定位首请求问题。

## 预置检查点/历史评估

`evaluate_coding.py:145`–161 预置system/user及十三个assistant/read-result配对：十二诊断和源码。seed-1至seed-12及seed-source唯一且非空白；每个结果紧跟调用，toolCallId/toolName精确匹配。参数是JSON对象。角色匹配当前枚举。缺失消息级元数据由源码兼容构造器默认空，调用元数据显式空。版本1、id evaluation、厂商/模型、RUNNING状态、ISO UTC时间戳符合当前SessionStore.validate。

普通临时路径上Python resolved工作区字符串应等于Java规范工作区，但强制精确相等；特殊规范化失败发生在prepare并产生NO_RESULT，而非代理FAILED JSON。预置未发现内在矛盾。未带行号源码/工具文本是合法历史，SessionStore/适配器不要求read_file格式。

`Resume`在代理执行前追加追问用户。压缩可删除完整旧周期并插入普通助手计数摘要，保留原始/最新用户及最新源码周期。通用Chat编码器直接写assistant tool_calls和tool role/name/tool_call_id，保留ID。源码追踪建立本地合法性，不代表厂商接受虚构预置ID或长历史。不能从本地拒绝推断网关故障，反之亦然。

## 诊断保留与下一隔离检查

评估器现于 `evaluate_coding.py:198` 存 `providerError: result.get('error')`；这是**代理错误**，可能完全本地。应重命名或解读为代理错误，非厂商故障证明。保留JSON状态、错误、退出码、迭代数、耗时、模式、产物哈希、是否解析JSON结果。NO_RESULT保留有界脱敏stderr/错误类别（非原始stdout/stderr），区分配置/检查点失败。负责人已增加脱敏诊断；不能暴露凭据、请求头、响应体、任意工具参数或原始cause链。

最安全下一隔离是用**相同预置检查点**和两种预算执行离线localhost，返回最小支持JSON完成或合法SSE序列，证明本地预置加载/压缩/请求编码，不再花模型调用。只比较请求结构计数：角色、调用/结果配对、上下文估算、序列化大小、输出上限字段、stream标记、是否首归一化响应返回。不要保存完整真实请求载荷诊断。

离线预置通过后，负责人可先分类已有脱敏真实错误，再决定额外、明确授权的真实探测。HTTP失败指向响应状态；缺失DONE/finish或严格JSON错误指向协议验证；预算错误仍本地。本审计不授权额外付费请求。
