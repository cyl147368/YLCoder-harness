# 自定义网关真实验收

用户已明确选择 <https://codegoai.com>，并向负责人提供凭据。只有负责人发起带认证的真实请求。绝不复制、查看、打印或保存凭据。不要运行网络或模型调用。此前指派已完成。原指派要求源码和文档使用英文；当前中文化以用户最新要求为准。

## cli_acceptance

仅负责 scripts/evaluate_coding.py 和交接文档 docs/CODEGO_EVALUATOR_REVIEW.md。在负责人使用前，审阅显式启用的真实回放评估器是否存在具体正确性问题。确保独立检查器使用新输出目录、显式空 classpath/sourcepath 和 -proc:none；编译失败或超时要报告安全状态。保留现有离线夹具、用量统计含义和对照。增加可选 --task TASK_ID 选择器（重复次数仍有限），以及正数 --max-output-tokens 参数（默认不设置）：通过显式临时真实配置传递，或仅在 CLI 已有支持时传递；不要修改生产代码。若 CLI 没有输出上限参数，跳过这一部分并记录配置用法。不要修改其他源码或文档、运行 Maven 或真实调用。其他成员也在工作，请保留他们的修改。负责人负责新网关配置、公开真实 harness 冒烟和文档。不要改变目标或自动化。

## responses_acceptance

仅负责 docs/CODEGO_PROTOCOL_AUDIT.md。阅读当前 OpenAiCompatibleProvider、NativeStreamTransport、OpenAiChatStream，并将配置、输出上限和流式行为与常见自定义 Chat 网关比较。为真实验收识别具体兼容性选择：原生流式或非流式、显式 max_tokens、实际用量。不要访问网络或修改源码。负责人报告响应之前，不要猜测 CodeGo 特定模型能力。其他成员也在工作，请保留其他修改。负责人处理真实调用。
