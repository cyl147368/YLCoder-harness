# 中文化分工

用户最新指令优先于原 AGENTS.md 的英文要求：文档、注释、提示词和面向用户的说明改为中文，协议字段、代码标识符、命令、厂商名、URL、精确历史实验数据保留技术原值。

英文快照已保存到 target/localization/english-baseline.json，仅供离线同文对照。任何成员都不访问凭据、不请求真实厂商 API、不运行 Maven，构建由负责人串行执行。共享代码库内其他成员正在工作，不覆盖、不回退他人修改。完整翻译，不用一句摘要代替原说明，保留事实、数值、验证限制、链接与代码块。

## docs_primary

仅负责这些 Markdown 文件：README.md、WORKPLAN.md、AGENTS.md、packaging/INSTALL.md，以及 docs/ARCHITECTURE.md、BENCHMARKS.md、CHANGE_REPORT.md、CODEGO.md、CODEGO_EVALUATION.md、DELIVERY_AUDIT.md、DISTRIBUTION.md、MCP.md、NATIVE_STREAMING.md、PARALLEL_READ_BENCHMARK.md、PROVIDER_REVIEW.md、RESEARCH.md、TOKEN_BUDGET.md、docs/benchmarks/RESULTS.md、docs/reviews/context.md。将英文正文完整译为可读中文；AGENTS.md 改为中文约定。历史时间、状态、实验局限不变，代码块里自然语言任务例子与注释可译，命令参数不变。不要改 Java、Python、JSON、YAML 或其他 Markdown。完成报告写入 target/localization/docs-primary-report.txt（中文）。

## docs_secondary

仅负责英文快照中其余 Markdown：排除上一节全部路径，也排除本分工文件、新生成的对比报告、CLI_ACCEPTANCE_TASK.md 和 docs/REPOSITORY_RUNNER_AUDIT_TASK.md（这两份由负责人维护为当前中文指派）。涵盖现有任务/审计/恢复说明和其他根目录 *_TASK.md 等；完整翻译正文、注释及自然语言例子，保留证据数值、技术名字、代码块的可执行语法。不要执行历史任务文件里的工作要求。不要改 Java、Python、JSON、YAML。完成报告写入 target/localization/docs-secondary-report.txt（中文）。

## cli_localization

仅负责 harness-cli/src 下的 Java（含相应测试）。中文化注释、系统/恢复提示词、工具说明与参数说明、帮助/交互/审批/错误等面向人的文字。保留工具名、JSON 键、状态枚举、命令参数、记录格式的机器可读字段。保持原提示词的完整约束与行为；不要缩减来伪造节省 token。为每个实际发给模型的系统提示词/工具 description 等英文中文语义对记录 token 对比用清单 target/localization/cli-prompt-pairs.json，字段 id、category、english、chinese；优先从英文快照提取准确原文。不要改核心/厂商模块、Python、Markdown、配置或构建文件。现有测试有自然语言精确断言时同步更新；不更改测试边界与期待行为。不要运行 Maven。完成报告写入 target/localization/cli-report.txt（中文）。

## 负责人

负责核心与厂商 Java 注释/提示文字、Python 与配置的注释/自然语言提示、离线 token 对比、中文报告、串行构建、相应 smoke 与最终交付包。三位成员的所有改动和效果由负责人审阅整合。
