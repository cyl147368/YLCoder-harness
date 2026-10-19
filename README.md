# YLCoder-harness

这是一个基于 Java 21 的编程智能体，提供 CLI、可恢复的对话、多种模型协议和受限的工作区工具。已验证结果、复验命令和已知限制见[验证记录](docs/VERIFICATION.md)。

项目名称为 **YLCoder-harness**，日常启动命令为 **`ylc`**。已有用户配置 `~/.code-harness/config.yaml` 和项目会话目录 `.harness` 保持兼容，无需迁移密钥或会话。Java 包名及 Maven 子模块名保留，以兼容现有扩展。

从零开始使用，请阅读根目录的 [中文使用手册](使用手册.md)，包含直接在配置文件填写密钥、日常任务、恢复和故障处理。

## 构建与运行

需要 JDK 21 和 Maven 3.9+。测试使用本地模拟厂商，不需要 API 密钥。打包后的冒烟测试可选用 Python 3。

```shell
mvn -B verify
java -jar harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar --help
python scripts/smoke_cli.py
python scripts/smoke_streaming.py
python scripts/smoke_native_streaming.py
python scripts/smoke_mcp.py
python scripts/smoke_token_budget.py
python scripts/smoke_message_metadata.py
python scripts/smoke_parallel_reads.py
python scripts/evaluate_coding.py
```

构建后在 PowerShell 运行一次 `.\install.ps1`，安装到当前用户 PATH。之后在任意项目目录执行 `ylc`，默认处理当前目录；不需要回到本仓库，也不需要每次指定 `--workspace`。只有 `-all.jar` 包含运行时依赖。

Windows 启动器会将当前控制台切换到 UTF-8（代码页 65001），并统一 Java 的默认编码、标准输出与错误输出编码。交互输入另行通过 Windows `ReadConsoleW` 直接读取 Unicode，避免中文在控制台字节转换中丢失；不需要修改系统区域设置。请优先通过最新安装的启动器运行。若直接调用 `java -jar`，应先执行 `chcp 65001`，并给 Java 添加 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`（放在 `-jar` 前）；请使用包含运行时依赖的 `-all.jar`。

验证后运行 `python scripts/package_distribution.py` 可构建便携包。ZIP、tar.gz 和 SHA256SUMS 将生成到 `target/distributions`。解压完整归档后，在包根目录运行 Windows 安装器 `install.ps1`，即可从任意目录调用 `ylc`。运行便携包需要 Java 21+，不需要 Maven 或 Python。详见[发行包安装与验证](docs/DISTRIBUTION.md)。

Windows 在源码目录或解压后的发行包根目录执行以下一次性安装。源码版需要先完成上面的 Maven 构建。安装器将运行时复制到 `$env:LOCALAPPDATA\YLCoder-harness`，加入用户 PATH，且只在缺少配置时创建 `$HOME\.code-harness\config.yaml`，不覆盖已有配置。

```powershell
.\install.ps1
notepad "$HOME\.code-harness\config.yaml"
```

在用户配置中填写 `base-url`、`model` 和 `api-key`，无需设置环境变量。模板使用 Chat Completions 兼容协议；使用其他协议时调整 `provider`。请将包含明文密钥的用户配置保留在本机。环境变量方式仍可选：将 `api-key` 留空，设置 `api-key-env` 指定的变量。更换厂商只需修改配置中的协议、地址、模型及对应凭据（全新协议扩展除外）。

日常使用可以在任意项目目录进行：

```powershell
cd "E:\path\to\repo"
ylc
ylc run "修复失败的解析器测试并验证修复结果"
ylc resume SESSION_ID "同时处理空输入"
ylc providers
ylc doctor
```

请将示例项目路径换成真实目录。安装器在当前 PowerShell 中执行时会刷新本终端 PATH；通过另一个进程安装时，完成后重新打开终端。更新运行时可再次运行安装器，已有用户配置保留。**在哪个目录执行 `ylc`，默认就以该目录为工作区。** YAML 中旧的 `workspace` 字段保留解析兼容，但不再改变工作区；只有显式传入 `--workspace "E:\另一个项目"`（或 `-w`）才会切换。`--config "C:\配置\另一个厂商.yaml"` 仅选择配置，不切换工作区。进入后输入 `/status` 可核对实际工作区。

不安装也能在任意目录用绝对路径启动：源码版 `& "E:\github\YLCoder-harness\ylc.cmd"`，便携版 `& "C:\工具\YLCoder-harness\bin\ylc.cmd"`。启动器根据自身位置找到 jar，并保留调用目录。Linux/macOS 可执行 `python3 scripts/install.py`，再自行把 `~/.local/share/YLCoder-harness/bin` 加入 PATH；Ubuntu Java 21 已通过公开 CI 的离线安装与运行验收，其他 Linux 发行版和 macOS 尚未验收。完整安装与故障处理见[中文使用手册](使用手册.md)及[验证记录](docs/VERIFICATION.md)。

不指定子命令时，CLI 可以连续接受任务。输入 `/project` 可立即查看本地工作区的顶层文件名，不调用模型、不读取文件正文；输入“分析当前项目”才会让模型选择文件并分析。`/status` 显示工作区、模型、厂商、接口协议、完整会话 ID、保存状态和当前进程累计用量，`/help` 显示帮助，`/exit` 退出。未知的斜杠命令会在本地提示，不会发送给模型。

启动界面显示 `ylc`、当前工作区和四个快捷命令。任务、回复与输入之间留出空白；工具名和文件路径合为一行，路径过长会缩略。

模型、厂商、输入/输出 token、缓存 token、缓存命中率、请求/工具次数和耗时集中到底部四行状态栏，不再每轮重复大块统计。支持 VT 的真实 Windows 控制台会固定底栏，并用一条细线与正文分隔，共占五行；管道、重定向、`--no-color`、`NO_COLOR`、过小窗口或不支持的终端使用输入提示符上方的紧凑文本状态区。完整模型名、接口协议、工作区、会话、累计用量和最近任务详情通过 `/status` 查看。自定义厂商只显示主机名；底栏过长的模型名会缩略，详情保留完整值。审核已报告用量计入本轮总量；“审核复用”与模型 token 缓存不同。没有工具调用时显示“已回复 · 未调用工具”，不表示已经检查或修改项目。

鼠标滚轮或终端滚动条可查看较早的输出。底栏保留全屏原生滚动，不设置会丢失历史的局部滚动区域；传统 Windows 控制台翻阅历史时暂缓底栏重绘。历史容量由终端设置决定。

缓存命中率按已报告请求的缓存输入 token 总数除以输入 token 总数计算，不平均各次请求的百分比。明确上报的 `0` 与未报告分开显示；部分请求缺失时标为“部分报告”，缓存率只反映输入和缓存均有报告的有效部分。输入为零或缓存数大于输入数时不显示正常命中率。`/status` 的“进程累计”仅覆盖本次启动后的请求，恢复会话不补入历史计费用量。它们是厂商响应统计，不是账单或实时逐 token 计数。完整说明见[中文使用手册](使用手册.md)。

支持颜色的终端默认显示少量强调色，`--no-color` 或 `NO_COLOR` 可关闭；输入或标准输出重定向时自动使用纯文本，单独重定向错误输出时请加 `--no-color`。`run` 的标准输出只保留回答正文，统计和进度写到标准错误流；`--json` 的正文不加交互标题。

部分兼容网关的流式响应不返回用量，会显示“未报告”；可使用 `ylc --no-stream` 获取普通响应中的用量（前提是厂商提供）。程序不会为补齐统计额外发送模型请求。

`run` 执行一个任务，并限制模型调用次数。`resume` 从同一个工作区重新加载对话记录，可附加后续要求。在 run/resume 中添加 `--json` 可输出机器可读结果，包含原有结果字段以及 `provider`、`model`、`providerHost`、`usageReport`。添加 `--quiet` 可关闭进度输出，保留最终统计及必要的人工审批提示。退出码：0 表示完成，1 表示失败，2 表示 CLI 用法错误，3 表示达到迭代上限，130 表示取消。模型声称完成并不代表代码正确，请检查报告中的验证结果。

交互任务共用一个会话，每个任务都会先保存最终状态，再接受下一个任务。EOF 和 `/exit` 都保留最近已保存的任务，并返回该任务的退出码；失败之后若后续任务成功，退出码会恢复为零。输入 `/status` 查看完整会话 ID 和恢复命令。第一个任务之前，`/help` 会说明尚未保存任务，恢复命令只在提交任务后有效。`python scripts/smoke_interactive.py` 覆盖管道任务、检查点、独立进程恢复、EOF 和失败恢复。`python scripts/smoke_console_input.py` 用于在 Windows 真实控制台中注入 Unicode 键盘事件，检查中文任务、工具和审批链路；这种自动化方式不等于验证每一种实体键盘或输入法。

## 配置

使用 [config.example.yaml](config.example.yaml)。配置优先级依次为显式 `--config`、`~/.code-harness/config.yaml`、默认值。仓库内的 `.harness/config.yaml` 默认被忽略；提供 `--trust-project-config` 后才会读取，而且优先于用户配置。显式 `--config` 和 `--trust-project-config` 授权配置文件选择端点、凭据变量和命令策略，因此使用前请审阅文件。CLI 参数覆盖已加载的配置项；工作区始终由调用目录或显式 `--workspace` 决定，不读取 YAML 的旧 `workspace` 值。相对工作区路径和显式配置路径以调用目录为基准。未知 YAML 键会被拒绝。

凭据支持配置中的 `api-key` 或环境变量引用 `api-key-env`。优先级为：显式 CLI `--api-key-env`、非空白的配置 `api-key`、配置 `api-key-env`、厂商内置回退变量。`header-env` 将额外 HTTP 请求头名称映射到环境变量名。配置密钥只在内存中传给厂商适配器，不写入会话或日志；YAML 解析错误也不回显配置值。省略 `base-url` 时使用厂商默认地址。恢复时需再次提供同一配置。可以通过参数主动切换厂商或模型。

CodeGo 仅用于已归档的真实验证。[configs/codego.example.yaml](configs/codego.example.yaml) 和 `CODEGO_API_KEY` 保留为可选的验证复现配置；日常使用从上面的通用模板配置自己的厂商。历史实测证据见 [CodeGo 配置与实测验收](docs/CODEGO.md)。

下表列出未设置 `api-key-env` 时的内置回退变量；显式配置优先。因此通用模板中的 `HARNESS_API_KEY` 可用于所选厂商，并不要求使用厂商名称命名变量。

| 厂商 ID | 协议 | 默认密钥变量 | 说明 |
|---|---|---|---|
| `openai` | 原生 OpenAI Chat Completions | `OPENAI_API_KEY` | 流式输出文本和完整工具调用；使用 `max_completion_tokens` |
| `openai-responses` | OpenAI Responses | `OPENAI_API_KEY` | 无状态文本/函数调用，回放工具轮次和最终轮次的加密推理 |
| `openai-compatible` | Chat Completions 兼容协议 | `OPENAI_API_KEY` | 原生流式输出与自定义 base URL；使用 `max_tokens` |
| `langchain4j-openai` | 通过 LangChain4j 1.22 使用 OpenAI | `OPENAI_API_KEY` | 真实流式文本、组装工具调用和 JDK 传输 |
| `anthropic` | Messages | `ANTHROPIC_API_KEY` | 合并工具结果；统计缓存输入 |
| `gemini` | generateContent | `GEMINI_API_KEY` | 保留受支持的 thought/text/function 部分的顺序、签名和调用 ID |
| `azure-openai` | Azure Chat Completions | `AZURE_OPENAI_API_KEY` | 必须提供 base URL；model 为部署名称 |
| `ollama` | Ollama 的 OpenAI 兼容端点 | 无 | 选择支持工具调用的本地模型 |

协议兼容不保证每个厂商模型都支持工具、每个网关都遵守同一种结构，或当前账户有权使用某个模型。尚未实现原生 Bedrock/Vertex 适配器。确切的兼容性限制见[厂商评审](docs/PROVIDER_REVIEW.md)。配置 MCP stdio 服务可以扩展工具集；协议和权限限制见 [MCP 配置](docs/MCP.md)。

添加自定义协议时，实现 `dev.codeharness.providers.ModelProvider`，并在 `META-INF/services/dev.codeharness.providers.ModelProvider` 中注册。把扩展 jar 放入 classpath，再以 `java -cp` 启动 `dev.codeharness.cli.HarnessCli`；不要使用 `java -jar` 启动扩展。OpenAI 兼容服务通常只需要配置。

`python scripts/smoke_custom_provider.py` 在无网络条件下验证扩展边界：它针对公开的 all-jar 编译厂商扩展，经 ServiceLoader 注册，运行实际写入/读取工具，在独立 CLI 进程中携带厂商元数据恢复，并确认 `providers` 可以发现扩展。扩展必须遵守公开 SPI 契约，并应针对精确的 YLCoder-harness 版本测试；SPI 记录类型变化后必须重新编译。

## 编程工具与权限

工具包括 `list_files`、字面量 `search_files`、限制行范围的 `read_file`、`write_file`、字面量 `edit_file`、`changes` 和 `run_command`。`edit_file` 只替换恰好一个匹配，可要求预期 SHA-256；匹配不唯一或内容过期时会失败。小范围编辑避免发送整个替换文件。文件写入使用原子替换，并拒绝经过符号链接。搜索会剪枝依赖/构建目录，跳过二进制或不可读文件。写入/编辑结果包含有界的上下文差异；`changes` 将已追踪文件与当前工具注册表中首次修改前的基线比较。恢复调用不会延续该基线。内存和输出限制见[变更报告](docs/CHANGE_REPORT.md)。

默认启用低风险命令自动审核，无需修改已有配置。有限的只读检查先经过本地候选规则，再交给独立上下文、没有工具权限的审核子 agent；只有两者都通过才执行，减少重复输入 `y`。审核复用当前配置的厂商和模型，只接收命令、规范工作区和实际执行参数，不接收聊天历史或项目正文。删除、安装、网络操作、解释器、构建/测试脚本和复杂 shell 仍需手动确认；项目测试能执行代码，不会因名称叫“测试”就自动放行。文件工具仍按原来的工作区边界直接读写；MCP 审批独立。

常用候选包括 `pwd`、`dir /b`、`ls` 和 `git branch --show-current`，完整固定写法见[使用手册](使用手册.md)。Git 当前分支查询要求工作区根目录有普通 `.git`；外置工作树和未通过边界检查的仓库转人工。自动执行使用审核过的绝对程序路径及固定参数，禁用 Git 全局/系统配置、fsmonitor、pager 和可选索引写入。`git status`、`git diff` 可能执行仓库定义的 clean/process filter，因此仍需确认，不能仅按“只读命令”名称放行。

审核失败、超时、答复不明确或达到次数上限时，交互终端回到 `y/N`；管道模式拒绝该操作，不会把下一条任务当作审批回答。同一任务内通过的完全相同工作区、命令和执行计划可复用审核结果；下一任务或新进程恢复会重新审核，手动批准也不会扩大后续权限。`ylc --no-auto-review` 或可信配置中的 `permissions.auto-review: false` 可恢复逐项手动审批。显式 `--allow-commands` / `permissions.allow-commands: true` 仍会预先批准所有命令，并优先于自动审核开关。

每次审核也是一次可能付费的模型请求。默认每项任务最多审核 8 次、每次最多等待 10 秒、输出上限为 96 tokens；次数和超时分别由 `permissions.max-auto-reviews`（1～32）与 `permissions.review-timeout-seconds`（1～30）控制，不占主智能体的 `agent.max-iterations`。审核用量单列在 `review` 统计中，顶层 `usage` 包含主智能体和审核已报告的用量；缺失用量不表示免费。`--quiet` 隐藏普通审核进度，仍显示需要人工确认的原因与提示。

命令在工作区启动，但拥有当前用户的文件系统和网络权限；自动审核不是操作系统沙箱。文件工具会强制检查规范工作区包含关系。命令有超时、有界输出，并尽力清理子孙进程。执行不可信代码时，应使用隔离的操作系统或容器环境。

任务输入和 shell/MCP 手动权限答复使用同一个输入读取器。真实控制台使用同一个控制台读取器处理两者；管道任务输入不会授予权限，也不会把下一项任务当作审批答复。审批提示写到 stderr，以保持 stdout 的 JSON 输出完整。手动审批时，EOF 或 `y`/`yes` 以外的任何回答都会拒绝操作。

会话保存在 `<workspace>/.harness/sessions`，其中包含提示词、代码片段、工具输出和不透明的厂商元数据。请保护该目录，并避免纳入版本控制。会话写入使用锁和原子替换。中断后，未知的工具效果会报告给模型供其检查，不会自动重放工具。检查点支持进程中断恢复，但不承诺断电后的持久性。

可信配置可启动 MCP 可执行文件及参数列表，不会隐式使用 shell。外部调用需逐次审批或提供 `--allow-mcp-tools`；shell 授权不会授予此权限。外部进程具有宿主权限，并在 CLI 调用结束后关闭。MCP 支持 stdio 上受限的工具发现与调用；尚未实现 HTTP 传输和其他 MCP 能力。

## 效率与验证

文档、注释、系统/恢复提示和 CLI 文案已中文化。相同首轮消息/工具载荷的本地计数：cl100k 为 711 → 1,009（+41.91%），o200k 为 721 → 861（+19.42%）。中文更便于阅读，但这组文本的 token 数更高；它不是 CodeGo 账单或多轮编程总消耗。完整范围、文档/注释分项和离线复算见 [中英文 token 对比](docs/LANGUAGE_TOKENS.md)。

使用 `--parallel-reads 4`（或 `agent.parallel-reads: 4`），可并行执行同一模型轮次返回的独立 list/search/read 调用。支持 1..8；默认 1 保持串行执行。修改、命令、变更报告和 MCP 工具构成执行屏障。结果和检查点保持调用顺序。只有可信、线程安全的只读扩展可以选择加入并行执行；取消是协作式的，不保证强制终止线程。证据见[并行读取评测](docs/PARALLEL_READ_BENCHMARK.md)。

- 稳定的工具顺序和递归排序的 schema 有助于复用提示词缓存。
- 搜索、读取和命令输出有界，防止工具响应过大。
- 本地上下文压缩保留指令、原始/最近任务和完整的近期工具循环。必需内容无法放入预算时会在本地失败。
- 输入估算包含消息、消息/工具元数据和 schema；厂商分词器与实际传输格式开销仍有差异。输出 token 上限单独控制。
- `--token-counter cl100k` 或 `--token-counter o200k` 为消息和 schema 选择本地 JSON 分词统计；YAML 对应项为 `agent.token-counter`。默认仍为 UTF-16 估算。预留量、缓存边界和厂商限制见 [token 预算说明](docs/TOKEN_BUDGET.md)。
- 结果报告厂商输入/输出/缓存计数和耗时；明确上报的零值正常显示，未报告的字段标明未知。JSON 的 `usageReport` 补充报告完整度和缓存率，读取程序不应仅凭 `usage` 中的零值判断请求免费。

确定性上下文夹具在保留已测试不变量的同时，将本地估算从 240,831 降至 448。夹具刻意包含重复输出；这不是测得的 API 费用降低，也不是编程质量基准。这里不主张真实模型提速或节省百分比。压缩可能省略旧细节，需要重新读取文件。

[基准套件](docs/BENCHMARKS.md) 提供带版本的代码/诊断/CJK 上下文，记录真实本地分词计数、字节数和压缩时间；也包含由 `javac` 和断言独立验证的成对 Java 修复。其本地策略是脚本化的，不伪造厂商用量。实测回放要求显式授权、配置和凭据；[CodeGo 验收](docs/CODEGO.md) 单独记录获授权的真实调用。原始结果暴露了 UTF-16 对 CJK 的低估；可选分词器模式现在会按自身统计规则执行本地预算。

运行 `python scripts/evaluate_multifile.py --repeat 2`，可执行成对的跨文件 API、发票溢出和分隔符修复，包括过期哈希拒绝、编译和未修改文件检查。独立检查器会拒绝有缺陷或只修复部分文件的对照组。这扩展了离线工作流覆盖，但不测量模型推理能力或真实厂商节省量。

`openai`、`openai-compatible`、`ollama`、`azure-openai` 和 `langchain4j-openai` 在生成过程中流式输出文本。网关拒绝流式请求时可用 `--no-stream`；网关返回普通 JSON 也会被接受，不会重试或编造增量。Responses、Anthropic 和 Gemini 仍为同步方式。本地 SSE 测试会保持完成状态未释放，直到观察到首个文本增量，并验证分片工具参数、用量、超时和中断。这证明可以提前交付文本，不代表测得了生产延迟改善。详见[原生流式输出](docs/NATIVE_STREAMING.md)。

`mvn verify` 测试核心行为、模拟厂商契约、配置、会话和文件系统边界。打包冒烟脚本通过实际 jar 执行搜索/读取/编辑，编译并运行临时 Java 文件，再恢复保存的会话。Windows 会跳过需要符号链接权限或 POSIX 权限的测试；Windows junction 包含关系测试会执行。

更多内容见[架构](docs/ARCHITECTURE.md)、[上下文评审](docs/reviews/context.md)和[验证记录](docs/VERIFICATION.md)。
