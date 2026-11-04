# YLCoder-harness

任务意图识别已加入：默认自动区分问答、分析、方案、修改和执行；用 `/intent` 查看，`/intent auto|chat|analyze|plan|edit|execute` 覆盖。设置仅影响当前进程，下一项任务生效。只读模式禁用 shell、写入和未知扩展工具，命令权限审核继续生效。详见 [任务意图识别](docs/TASK_INTENT.md)。

内置联网搜索 `web_search` 和网页读取 `web_fetch`，适用于最新新闻、官方文档、版本与报错查询。默认无需额外搜索密钥：使用 Bing RSS，失败时尝试 DuckDuckGo；也可通过 `web.provider: searxng` 与 `web.search-url` 指定自建搜索服务。`ylc --no-web` 可关闭联网工具，已有模型厂商配置无需修改。

交互界面用青色“你 ›”标记提问、紫色“◆ YLCoder”标记回答；底栏按模型、输入/输出、缓存和执行状态分色，保留终端原生滚动历史。`--no-color` 或 `NO_COLOR` 可使用纯文本。

交互回答支持终端 Markdown 排版：标题、粗体、斜体、删除线、行内代码、代码块、列表、引用、链接和基础表格会转换为可读的终端样式。为避免流式输出显示半截标记，交互模式会在本轮答案完整后排版；JSON 输出仍保留原始 Markdown，便于脚本处理。

默认生成本地 Markdown 执行日志，记录每次实际模型请求、回复、重试和工具结果摘要；重复提示词引用首次记录。交互中输入 `/logs`，终端执行 `ylc logs` 查看文件位置，详见[执行日志使用说明](docs/EXECUTION_LOGS.md)。

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
ylc logs
```

请将示例项目路径换成真实目录。安装器在当前 PowerShell 中执行时会刷新本终端 PATH；通过另一个进程安装时，完成后重新打开终端。更新运行时可再次运行安装器，已有用户配置保留。**在哪个目录执行 `ylc`，默认就以该目录为工作区。** YAML 中旧的 `workspace` 字段保留解析兼容，但不再改变工作区；只有显式传入 `--workspace "E:\另一个项目"`（或 `-w`）才会切换。`--config "C:\配置\另一个厂商.yaml"` 仅选择配置，不切换工作区。进入后输入 `/status` 可核对实际工作区。

不安装也能在任意目录用绝对路径启动：源码版 `& "E:\github\YLCoder-harness\ylc.cmd"`，便携版 `& "C:\工具\YLCoder-harness\bin\ylc.cmd"`。启动器根据自身位置找到 jar，并保留调用目录。Linux/macOS 可执行 `python3 scripts/install.py`，再自行把 `~/.local/share/YLCoder-harness/bin` 加入 PATH；Ubuntu Java 21 已通过公开 CI 的离线安装与运行验收，其他 Linux 发行版和 macOS 尚未验收。完整安装与故障处理见[中文使用手册](使用手册.md)及[验证记录](docs/VERIFICATION.md)。

不指定子命令时，CLI 可以连续接受任务。输入 `/project` 可立即查看本地工作区的顶层文件名，不调用模型、不读取文件正文；输入“分析当前项目”才会让模型选择文件并分析。`/model` 打开模型选择列表，合并当前、配置、最近会话和兼容网关 `/models` 的候选；也可用 `/model 序号` 或 `/model 模型名` 直接切换。恢复旧会话后同样可以覆盖检查点中的旧模型；切换不调用推理模型，下一条任务生效并持久化。`/status` 显示工作区、模型、厂商、接口协议、完整会话 ID、保存状态和当前进程累计用量，`/logs` 列出当前会话最近的执行日志，`/help` 显示帮助，`/exit` 退出。未知的斜杠命令会在本地提示，不会发送给模型。

启动界面显示 `ylc`、当前工作区和四个快捷命令。任务、回复与输入之间留出空白；工具名和文件路径合为一行，路径过长会缩略。

模型、厂商、输入/输出 token、缓存 token、缓存命中率、请求/工具次数和耗时集中到底部四行状态栏，不再每轮重复大块统计。支持 VT 的真实 Windows 控制台会固定底栏，并用一条细线与正文分隔，共占五行；管道、重定向、`--no-color`、`NO_COLOR`、过小窗口或不支持的终端使用输入提示符上方的紧凑文本状态区。完整模型名、接口协议、工作区、会话、累计用量和最近任务详情通过 `/status` 查看。自定义厂商只显示主机名；底栏过长的模型名会缩略，详情保留完整值。审核已报告用量计入本轮总量；“审核复用”与模型 token 缓存不同。没有工具调用时显示“已回复 · 未调用工具”，不表示已经检查或修改项目。

鼠标滚轮或终端滚动条可查看较早的输出。底栏保留全屏原生滚动，不设置会丢失历史的局部滚动区域；传统 Windows 控制台翻阅历史时暂缓底栏重绘。历史容量由终端设置决定。

缓存命中率按已报告请求的缓存输入 token 总数除以输入 token 总数计算，不平均各次请求的百分比。明确上报的 `0` 与未报告分开显示；部分请求缺失时标为“部分报告”，缓存率只反映输入和缓存均有报告的有效部分。输入为零或缓存数大于输入数时不显示正常命中率。`/status` 的“进程累计”仅覆盖本次启动后的请求，恢复会话不补入历史计费用量。它们是厂商响应统计，不是账单或实时逐 token 计数。完整说明见[中文使用手册](使用手册.md)。

支持颜色的终端默认显示少量强调色，`--no-color` 或 `NO_COLOR` 可关闭；输入或标准输出重定向时自动使用纯文本，单独重定向错误输出时请加 `--no-color`。`run` 的标准输出只保留回答正文，统计和进度写到标准错误流；`--json` 的正文不加交互标题。

部分兼容网关的流式响应不返回用量，会显示“未报告”；可使用 `ylc --no-stream` 获取普通响应中的用量（前提是厂商提供）。程序不会为补齐统计额外发送模型请求。

模型请求收到 HTTP 408/429/500/502/503/504 时，会保持当前 Chat/Responses 协议及流式开关，按 **1、2、4 秒**等待，最多重试 **3 次**。流式请求遇到 HTTP 400、可降级的 SSE 错误、断连或超时，且尚未输出文本时，才切换到非流式；兼容降级与暂时性 HTTP 重试共用一个预算，单轮包含初次请求最多 4 次。400 降级后本进程继续使用非流式，临时 HTTP 失败不会关闭流式。重试不追加消息、不重放已完成工具，失败请求也计入统计。已显示部分文本、用户取消或明确的 HTTP 401/403/404/413/422 等失败不继续重试；Esc / Ctrl+C 可中断等待。配置 `agent.stream-fallback: false` 仅关闭自动兼容降级，不关闭上述临时 HTTP 重试。交互中可用 `/stream off`、`/stream on` 切换，`/stream` 查看状态。

原生 HTTP 适配器和 Chat SSE 错误会显示厂商的 `message`、`code`、`type`、`param` 等诊断字段，也支持短纯文本错误；配置中的密钥、自定义请求头值和常见凭据格式先脱敏，移除终端控制字符，摘要最多 1024 字符。超大错误正文、HTML 和损坏的 JSON 会提示已省略。JSON 模式在最终 `error` 字段返回摘要，不混入重试进度。网络层没有厂商错误正文时，显示连接拒绝、DNS 失败、超时或连接提前关闭等原因及异常类型，不再统一显示“流式请求失败”；不输出底层异常中的地址、请求头或报文。

`run` 执行一个任务；流式降级最多额外发起 3 个请求。`resume` 从同一个工作区重新加载对话记录，可附加后续要求。在 run/resume 中添加 `--json` 可输出机器可读结果，包含原有结果字段以及 `provider`、`model`、`providerHost`、`usageReport`。添加 `--quiet` 可关闭进度输出，保留最终统计及必要的人工审批提示。退出码：0 表示完成，1 表示失败，2 表示 CLI 用法错误，3 表示达到迭代上限，130 表示取消。模型声称完成并不代表代码正确，请检查报告中的验证结果。

交互任务共用一个会话，每个任务都会先保存最终状态，再接受下一个任务。EOF 和 `/exit` 都保留最近已保存的任务，并返回本次进程在当前会话中最近任务的退出码；失败之后若后续任务成功，退出码会恢复为零。输入 `/resume` 查看当前工作区最近的历史会话，再用 `/resume 序号` 或 `/resume 完整会话ID` 切换；切换本身不调用模型，继续输入任务才会发起请求。恢复失败保留原会话，成功切换后不会混入原会话的对话。`/status` 查看完整会话 ID 和恢复命令。第一个任务之前，`/help` 会说明尚未保存任务。`python scripts/smoke_interactive.py` 覆盖管道任务、检查点、独立进程恢复、EOF 和失败恢复。`python scripts/smoke_console_input.py` 用于在 Windows 真实控制台中注入 Unicode 键盘事件，检查中文任务、工具和审批链路；这种自动化方式不等于验证每一种实体键盘或输入法。

恢复后立即显示最近一页历史并在该会话继续输入。`/history`、`/history 页码`、`/history all` 可查看更多历史；系统提示和工具原始载荷不展开，长消息有显示上限，保存的上下文保持完整。正常交互终端由 JLine 提供 Tab 命令补全和方向键编辑，不接管鼠标滚轮，也不将输入历史另存到磁盘。`python scripts/smoke_console_input.py --completion` 验证实际 Tab 键、恢复历史、中文任务和审批输入。

交互中按一次 `Ctrl+C` 中断当前任务并退出，也适用于等待输入、模型响应和命令审批。程序保留可恢复的检查点、释放会话锁并恢复终端，返回退出码 `130`；未提交的输入和不完整的流式回复不会作为完整消息保存。重新启动后可用 `/resume`、`/resume 1` 恢复。`python scripts/smoke_console_input.py --interrupt` 使用 localhost 夹具和真实 Windows 控制台键盘事件验证这些行为。

按 `Esc` 取消当前任务但保留交互会话，空闲时清空输入草稿；在 500 毫秒内连按两次 `Esc` 打开历史回退选择，输入编号后回到该提问之前，并将所选提问预填到输入框中，可编辑后直接发送。也可输入 `/rewind` 或 `/rewind 序号`。回退继续使用当前会话 ID，不增加 `/resume` 列表项；回退前的完整历史保存在同一检查点中，可用 `/history previous` 查看最近一次快照。回退不请求模型、不重放工具、不撤销实际文件修改。请求上下文压缩不删除已保存的历史记录。按键需要正常 JLine 交互终端；管道或 `TERM=dumb` 可使用 `/rewind`。`python scripts/smoke_console_input.py --escape` 验证真实 Windows 控制台中的取消、继续输入和回退。

## 配置

使用 [config.example.yaml](config.example.yaml)。配置优先级依次为显式 `--config`、`~/.code-harness/config.yaml`、默认值。仓库内的 `.harness/config.yaml` 默认被忽略；提供 `--trust-project-config` 后才会读取，而且优先于用户配置。显式 `--config` 和 `--trust-project-config` 授权配置文件选择端点、凭据变量和命令策略，因此使用前请审阅文件。CLI 参数覆盖已加载的配置项；工作区始终由调用目录或显式 `--workspace` 决定，不读取 YAML 的旧 `workspace` 值。相对工作区路径和显式配置路径以调用目录为基准。未知 YAML 键会被拒绝。

凭据支持配置中的 `api-key` 或环境变量引用 `api-key-env`。优先级为：显式 CLI `--api-key-env`、非空白的配置 `api-key`、配置 `api-key-env`、厂商内置回退变量。`header-env` 将额外 HTTP 请求头名称映射到环境变量名。配置密钥只在内存中传给厂商适配器，不写入会话或日志；YAML 解析错误也不回显配置值。省略 `base-url` 时使用厂商默认地址。恢复时需再次提供同一配置。可以通过参数主动切换厂商或模型。

自定义厂商支持 Responses 接口时，可保留 `provider: openai-compatible`，增加 `api-format: responses`，或启动时传 `ylc --api-format responses`。`base-url` 填 API 根地址，例如 `https://your-provider.example/v1`，不要附加 `/responses` 或 `/chat/completions`。`api-format` 只适用于 `openai`、`openai-compatible`、`openai-responses`，取值为 `chat` 或 `responses`；省略字段保持所选 provider 原有默认协议。Responses 支持 SSE 文本、函数调用和加密推理回放；流式失败后的非流式重试仍访问同一 `/responses` 接口。

CodeGo 仅用于已归档的真实验证。[configs/codego.example.yaml](configs/codego.example.yaml) 和 `CODEGO_API_KEY` 保留为可选的验证复现配置；日常使用从上面的通用模板配置自己的厂商。历史实测证据见 [CodeGo 配置与实测验收](docs/CODEGO.md)。

下表列出未设置 `api-key-env` 时的内置回退变量；显式配置优先。因此通用模板中的 `HARNESS_API_KEY` 可用于所选厂商，并不要求使用厂商名称命名变量。

| 厂商 ID | 协议 | 默认密钥变量 | 说明 |
|---|---|---|---|
| `openai` | 原生 OpenAI Chat Completions | `OPENAI_API_KEY` | 流式输出文本和完整工具调用；使用 `max_completion_tokens` |
| `openai-responses` | OpenAI Responses | `OPENAI_API_KEY` | SSE 文本/函数调用；无状态回放工具轮次和最终轮次的加密推理 |
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

默认权限模式是 `review`：先做本地路径和执行条件检查，明确安全的查询直接放行；其余受支持的查询与常规 Maven 构建/测试交给没有执行工具的审核模型。删除、安装、任意脚本和复杂 shell 仍需手动确认；模型不能扩大已支持的执行计划范围。内置文件工具仍按工作区边界直接读写；MCP 审批独立。

对于没有命中本地固定计划、但 shell 结构仍可确定解析且不含硬危险命令的查询，会按参考项目的流程交给无工具分类器。分类器只能批准明显的只读检查；删除、写入、安装/发布、解释器、敏感路径、凭据读取和网络上传在本地直接拦截。分类器不可用、超时或返回人工结论时仍会询问 `y/N`。

| 操作 | `review` 模式的实际处理 |
| --- | --- |
| `pwd`、`cd`、受支持的 `ls/dir`、固定 `find AGENTS.md`、`where /r . AGENTS.md` | Java 原生只读查询；不调用审核模型、不启动 shell 或外部程序 |
| `rg --files`、受支持的 rg 正文搜索，可限定多个相对目录并加单个 `head` 结果上限 | Java 原生查询；不调用审核模型，也不启动 shell、rg 或 head |
| `git status` 支持的只读选项、`git branch --show-current` | 通过原有仓库、程序路径及配置检查后本地放行；执行固定参数的外部 Git |
| `git clean -nd` 等清理预览、受支持的 `date/Get-Date` | 先生成受限计划，再调用审核模型；批准后执行固定参数 |
| 标准 Maven 3.9.x 的 `mvn/mvn.cmd validate/compile/test-compile/test/package/verify` 及受支持选项 | 本地绑定项目配置和外部 Java/Maven 安装，交给审核 AI；批准后直接执行固定参数 |
| 实际清理、安装/发布、任意写入命令、其他构建/测试脚本、其他管道或重定向等 | 交互终端人工确认；无交互终端时拒绝 |

常规 Maven 命令支持 `-pl`、`-am`、`-o` 和通过检查的 `-D` 参数，命令上限为 4096 字符。例如 `mvn -o -pl server -am -DskipTests=false -Dtest=ApiKeyFilterTest,MarkdownPathResolverTest -Dsurefire.failIfNoSpecifiedTests=false test` 会进入审核 AI。审核包含当前用户任务、目标、模块及项目插件/扩展摘要；批准后启动绑定的外部 Java 和 Maven，不通过 shell 或工作区包装器。项目配置或运行时改变会使旧批准失效。Maven 3.8/4、`clean/install/deploy`、插件目标、外部 settings、JVM 代码加载参数和任意命令串接仍需人工确认。

Maven 构建会执行插件与测试代码，可写入文件和联网，**不是只读操作，也不是沙箱**。`-o` 只限制 Maven 下载依赖，不能阻止项目插件或测试自行联网。

原生文件遍历不跟随符号链接或 junction，递归排除 `.git/.harness`，默认最多访问 10,000 项、返回 1,000 项，限制输出为 11,000 字符、深度为 32，并在遍历中检查 2 秒时间预算及中断。rg 兼容查询不读取 `.gitignore/.ignore`，因此可能列出或搜索被 Git 忽略的普通文件；结果会提示这一差异。这是受限查询，不是完整的 rg/find/where 实现。具体写法见[使用手册](使用手册.md)。

`review` 模式支持 `rg --files` 多目录文件发现及正文搜索，最多指定 32 个工作区内相对目录或文件。`-g/--glob` 接受 `*`、`?`、`**` 和 `!` 排除模式，例如 `-g '*.java' -g '!target'`，不支持 `[]/{}`。正文支持 `-n` 行号、`-i` 忽略大小写、`-F` 字面文本、`-e` 多模式及对应长选项；正则使用 RE2J，不支持 PCRE 回溯引用或前后查找。引号内的 `|` 是正则内容，末尾唯一的 `| head -200`、`| head -n 200` 或 `| head --lines=200` 会转为结果数上限，范围 1～1000；无需安装 head。命令最多 4096 字符、64 组 glob；head 和其他预算先达到者截断。

`rg --files` 输出可在 `head` 前加入单个路径前缀裁剪变换，例如 `| sed 's#server/src/main/java/##'`。程序只接受空替换目标 `s#前缀##`，在 Java 内存中处理文件名，不启动 `sed` 或读取文件正文；任意 sed 脚本、替换内容或非路径变换仍会转人工。

```text
rg --files server/src client/src | head -200
rg -n "@RestController|@Service|Redis" server/src infra/src | head -240
```

正文搜索每文件最多读取 1,000,000 字节，每查询累计最多 8,000,000 字节；跳过过大、含零字节或无效 UTF-8 的文件，并排除 `.env`、凭据目录、私钥等常见敏感路径，即使显式指定或加 `--hidden` 也不会读取。它不保证识别普通源码中的所有秘密；应避免把秘密写入代码。变量/命令展开、重定向及其他管道不会通过该快速路径。

`where /r . AGENTS.md` 与 `rg/find` 共用上述原生执行器，在 `review` 模式直接本地放行；也接受 `where.exe`、`/R`、用引号包裹当前目录 `.`，以及多个既有受限文件名模式，例如 `where.exe /R "." AGENTS.md README.md "*.java"`。查询仅限当前工作区，输出匹配文件的绝对路径，不搜索 PATH、不展开 PATHEXT；没有匹配时按原生查询统一返回成功，并提示“未找到匹配文件”。它不调用外部 where，也不支持任意目录或未知模式。

Git 查询要求工作区根目录有普通 `.git`，并绑定仓库配置指纹。过滤器、外部或条件配置、部分克隆及无法可靠识别的配置会转人工；精确的 `git branch --show-current` 不读取工作树，不因存在过滤器配置而被拦截，其他配置检查仍生效。固定执行参数禁用 Git 全局/系统配置、fsmonitor、pager、懒加载联网和可选索引写入；状态查询不深入子模块。审核后会重新核对执行条件，变化会使批准失效。

在 `review` 模式，常见只读差异查询也走本地快速路径，无需输入 `y`、无需调用审核 AI：

```shell
git diff
git diff -- build.sh pom.xml soulclaw-knowledge-server/src/main/resources/application.yml
git diff --stat
git diff --name-only
git diff --cached --name-status
git diff -- "中文 文件.txt"
```

支持 `--cached/--staged` 和单个输出格式 `--stat/--numstat/--shortstat/--name-only/--name-status`，选项写在 `--` 前，限定文件或目录写在 `--` 后。路径使用工作区内相对路径和 `/` 分隔符；含空格可用整参数单引号或双引号。保留安全参数 `--no-ext-diff/--no-textconv/--ignore-submodules=all/--no-color` 也可使用。系统直接启动固定 Git 程序并隔离 Git 环境及全局/系统配置；只查看暂存区或当前工作区，不支持任意提交比较、通配符、路径魔法或输出写文件。无 pathspec 的差异在审批阶段不扫描整个工作区，因此无关的 `.harness` junction 不会造成误判；实际未暂存差异仍读取受跟踪文件，不能认为所有受跟踪路径都已逐一核验。显式路径含链接/junction、特殊文件、越界或不可访问时仍转人工。单次最多 64 个路径，命令上限 4,096 字符。不存在的文件可查询删除差异，但尚存父目录也需通过检查。

小范围只读命令链会先拆为明确的参数和连接操作符。当前本地支持 `git status`、受支持的 `git diff`、无 revision/pathspec 的 `git log --oneline -N`、工作区内多目录 `ls`、安全字面量 `echo`、`head`、`tail`、`wc -l` 和普通文件的 `wc -c`；最多 12 段，使用 `&&`、`;` 或单个 `|` 连接。命令审核上限统一为 4,096 字符，不再对普通命令单独限制为 128 字符。`2>/dev/null` 丢弃 stderr，Windows 改写为 `2>NUL`；`2>&1` 保留合并语义。ls 保留选项和目录，`*/` 展开为最多 128 个非隐藏目录；ls/wc 检查操作数与祖先路径。全部程序、配置和路径复核通过后本地放行，不调用审核模型。

剩余可解析、通过安全检查的查询可交给无工具分类器；AI 和本地路径共用解析结果，执行绑定的外部程序及重新生成的参数。AI 不可用、建议人工确认或计划变化时仍询问用户。当前是有界语法实现，没有移植参考项目完整的 Bash AST；不支持 `||`、变量/命令展开及文件读写重定向，也不构成操作系统沙箱。


用 `ylc --approval-mode review`、`manual` 或 `allow-all` 切换模式，或设置 YAML `permissions.mode`。`manual` 对 shell 逐项确认；`allow-all` 预先允许所有 shell 命令。CLI 优先级为显式 `--approval-mode` → `--allow-commands` → `--no-auto-review` → YAML。YAML 显式 `permissions.mode` 优先于旧布尔开关；省略 mode 时，`allow-commands: true` 优先，否则由 `auto-review` 决定 review/manual。原有配置继续兼容。

交互任务输入框按 **Shift+Tab**（备用 **Alt+M**）在 `review` 与 `manual` 之间切换，保留未提交的草稿；也可输入 `/approval review`、`/approval manual`，或用 `/approval` 切换。底栏和 `/status` 显示当前权限模式。交互选择仅影响本进程，重启恢复启动设置，不写配置、不调用模型。任务执行及审批期间不切换模式；`allow-all` 不参与快捷循环，须重启并显式选择其他模式。

原生计划检查失败、模型失败、超时、答复无效、建议人工确认或审核额度耗尽，都不会自动批准原命令；交互终端转 `y/N`，管道模式拒绝。原生查询执行时若发现目录身份变化，则中止，不自动降级执行 shell。同一任务内完全相同且仍有效的 AI 批准计划可复用；新任务和重新启动后不复用，手动批准也不扩大后续权限。

审核模型默认沿用主连接和格式，也可以单独设置 `permissions.reviewer.provider/model/base-url/api-format/api-key/api-key-env/header-env`。例如只设置 `reviewer: {model: YOUR_REVIEW_MODEL_ID}` 可使用同一厂商的另一模型；只设置 `reviewer: {api-format: responses}` 可在同一连接改用 Responses，并继承密钥和请求头。更换厂商或端点时不会自动转发主模型密钥和额外请求头；请配置审核凭据，或使用目标厂商默认密钥变量。跨厂商省略 `base-url` 时使用目标厂商默认地址。同一连接省略 `header-env` 会继承，显式 `{}` 会清空。完整例子见[使用手册](使用手册.md)。

审核请求包含当前命令、规范工作区、实际执行参数，以及有界的用户消息和工具调用摘要；不转发助手自由文本、工具结果、写入正文或替换正文。分类器没有执行工具，只能返回结构化 `decision` 和可选简短 `reason`。原始模型理由不显示或写入日志，界面使用固定审核状态提示；返回格式无效即转人工。

只有实际调用审核模型才增加一次可能付费的请求。默认每项任务最多 8 次、每次最多等待 10 秒、输出上限 192 tokens；前两项分别由 `permissions.max-auto-reviews`（1～32）和 `permissions.review-timeout-seconds`（1～30）控制。JSON `review` 单列请求、用量、`localApprovals`（本地放行数）及 `elapsedMillis`（等待审核模型的累计毫秒数），`cacheHits` 是批准结果复用次数。顶层 `usage` 包含主模型与审核已报告用量；缺失用量不表示免费。`--quiet` 隐藏普通审核进度，仍保留人工确认提示。

命令在工作区启动，但拥有当前用户的文件系统和网络权限；自动审核不是操作系统沙箱。文件工具会强制检查规范工作区包含关系。命令有超时、有界输出，并尽力清理子孙进程。执行不可信代码时，应使用隔离的操作系统或容器环境。

任务输入和 shell/MCP 手动权限答复使用同一个输入读取器。真实控制台使用同一个控制台读取器处理两者；管道任务输入不会授予权限，也不会把下一项任务当作审批答复。审批提示写到 stderr，以保持 stdout 的 JSON 输出完整。手动审批时，EOF 或 `y`/`yes` 以外的任何回答都会拒绝操作。

会话保存在 `<workspace>/.harness/sessions`，其中包含提示词、代码片段、工具输出和不透明的厂商元数据。请保护该目录，并避免纳入版本控制。会话写入使用锁和原子替换。中断后，未知的工具效果会报告给模型供其检查，不会自动重放工具。检查点支持进程中断恢复，但不承诺断电后的持久性。

程序在会话目录内创建 `.gitignore`，默认排除所有会话及锁文件，让普通 `git clean -fd` 保留当前会话；不会修改项目根 `.gitignore`，已有会话忽略文件保持原样。已跟踪的会话文件不受忽略规则影响，`git clean -fdx` 等强制清理也能删除忽略文件。若目录被外部删除或变得不可写，交互模式会报告保存失败、保留内存历史并暂停新任务，可用 `/history all` 查看；保留需要的内容后退出重启，不会静默重建旧锁或重放工具。

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

`openai`、`openai-compatible`、`openai-responses`、`ollama`、`azure-openai` 和 `langchain4j-openai` 在生成过程中流式输出文本。网关拒绝流式请求时可用 `--no-stream`；网关返回普通 JSON 也会被接受，不编造增量。Responses 使用自己的 SSE 事件和 `/responses` 接口，支持函数与加密推理回放；非流式恢复不切换到 Chat。Anthropic 和 Gemini 仍为同步方式。本地 SSE 测试验证提前文本、分片工具参数、用量、超时和中断。这证明可以提前交付文本，不代表测得了生产延迟改善。详见[原生流式输出](docs/NATIVE_STREAMING.md)。

`mvn verify` 测试核心行为、模拟厂商契约、配置、会话和文件系统边界。打包冒烟脚本通过实际 jar 执行搜索/读取/编辑，编译并运行临时 Java 文件，再恢复保存的会话。Windows 会跳过需要符号链接权限或 POSIX 权限的测试；Windows junction 包含关系测试会执行。

更多内容见[架构](docs/ARCHITECTURE.md)、[上下文评审](docs/reviews/context.md)和[验证记录](docs/VERIFICATION.md)。
