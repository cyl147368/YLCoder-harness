# 当前审阅分工（历史任务记录）

## 自定义网关审计

responses_acceptance：阅读 docs/CODEGO_TASKS.md 中负责的章节。只有负责人处理凭据或发起真实请求；仅负责 docs/CODEGO_PROTOCOL_AUDIT.md。此前指派已完成。

## 交互工作流审阅

下面较早的指派均已完成。responses_acceptance 仅负责 docs/INTERACTIVE_AUDIT.md。阅读 HarnessCli 的 interactive/execute/prepare 流程和测试。负责人正在实现真实 JAR、管道输入的双任务交互会话，以及独立进程恢复冒烟。识别与终端交互输入、检查点及完成状态相关的具体工作流缺陷或缺失验收。不要修改源码或测试，不要运行 Maven 或真实调用；保留其他修改。重点审阅 run/resume 尚未覆盖的交互行为，并报告证据。不要推测性扩大范围。

## 多文件评估器审计

下面较早指派均已完成。responses_acceptance 仅负责 docs/MULTIFILE_EVAL_AUDIT.md。阅读 scripts/evaluate_multifile.py（负责人新建的运行器）和 scripts/multifile_fixtures.py（另一成员仍在改进）。针对跨文件上下文、工具和恢复正确性的结论，寻找具体误判通过或方法问题。不要修改源码、运行 Maven 或真实调用。负责人独立执行检查器对照；重点关注全新验证器隔离、结果/顺序保留、汇总指标和准确结论。其他成员也在工作，请保留他们的修改。在负责的审计文件中简洁记录证据和所需修复。

## 命令清理指派

下面所有指派均为已完成历史。responses_acceptance：阅读 `docs/COMMAND_CLEANUP_TASK.md`，以其为新任务的权威依据。仅负责 CLI tools 包中新建的 `CommandProcessCleanup.java` 和 `CommandProcessCleanupTest.java`。实现有界异步后代进程/管道清理，以及操作卡住的确定性测试。负责人修改 WorkspaceTools 并集成 API。不要运行 Maven。其他成员也在工作，请保留其他修改。

## 原生流式指派

阅读 docs/NATIVE_STREAM_TASKS.md。responses_acceptance 负责原生解析器/厂商集成；gemini_continuity 负责验收测试；cli_acceptance 负责打包冒烟。负责人负责传输辅助类和基类 client 可见性。不要运行 Maven，保留其他修改。此前审阅已完成。

## 只读审阅

responses_acceptance：审计当前并行读取实现中的具体正确性缺口。仅负责 `docs/PARALLEL_READ_AUDIT.md`；不要修改生产代码或测试，不要运行 Maven。此前并行实现任务已完成。负责人检查模型回调后的取消行为，请关注其他方面。保留其他修改。

## 有界并行读取指派

此前消息元数据任务已完成。阅读 `docs/PARALLEL_READ_TASKS.md`，以其为当时的权威指派。responses_acceptance 仅负责核心验收测试；cli_acceptance 负责 CLI 配置和能力连接；gemini_continuity 负责离线基准。负责人负责核心生产代码并串行运行 Maven。不要运行 Maven 或真实调用。保留其他修改。

## 消息元数据指派

阅读 `docs/MESSAGE_METADATA_TASKS.md`：它是 responses_acceptance、gemini_continuity、cli_acceptance 的权威明文指派。实现各自负责章节。负责人增加核心 SPI 并串行运行 Maven。不要运行 Maven 或付费调用，保留其他修改。responses_acceptance 仅负责 OpenAiResponsesProvider、厂商测试和文档，实现最终文本回合的推理回放。下面较早的结束标记指派是已完成历史。

## 厂商修复指派（responses_acceptance）

实现 docs/PROVIDER_FOLLOWUP.md 报告的缺失/null/非文本结束标记拒绝。仅负责 harness-providers/src/main/java/dev/codeharness/providers/OpenAiCompatibleProvider.java、AnthropicProvider.java 和厂商测试。增加必要 localhost 适配器情况，以及记录工具调用的 CodeAgent 集成断言，确保格式错误的结束响应永不执行修改或完成。保留显式识别的结束状态。不要运行 Maven（负责人串行构建）。交接写入 docs/PROVIDER_FINISH_REPAIR.md。负责人负责核心和 CLI tokenizer 修改。其他成员也在工作，请保留其他修改。

负责人负责核心 token 统计、CLI tokenizer 集成及相关测试。不要编辑这些源码或并行运行 Maven。

`cli_acceptance`：只读审计 SessionStore 和 CodeAgent 检查点恢复质量。识别可复现正确性问题或缺失的必要验收。将证据和复现/测试建议写入 docs/RECOVERY_FOLLOWUP.md。不要作其他修改。其他成员也在代码库工作，请保留他们的成果。原任务要求英文，现按用户最新要求翻译。

`responses_acceptance`：只读审计厂商结束状态和推理/流式连续性。识别最严重的具体正确性问题及复现/测试建议。证据写入 docs/PROVIDER_FOLLOWUP.md。不要作其他修改。其他成员也在工作，请保留他们的成果。原任务要求英文，现按用户最新要求翻译。

任务消息无法读取时，本文件是指派来源。仅使用此项目目录。

# 最近一次 responses_acceptance 指派

阅读 docs/LATENCY_AUDIT_TASK.md。仅负责 docs/LATENCY_AUDIT.md。早期夹具已交还负责人。不要访问凭据或控制器、发起真实请求、修改源码或运行 Maven。负责人负责真实运行器和打包。其他成员也在工作，不覆盖他们的成果。
