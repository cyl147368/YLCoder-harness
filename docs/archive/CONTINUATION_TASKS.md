# 续接工作归属

目标续接明确要求继续追求原始最终成果。补齐原始缺口，不声称已达到过期的十小时截止时间。原指派要求源码/文档仅用英文，当前按用户最新要求中文化。仅使用本地模拟。不要检查项目外部。不要并发运行 Maven；由负责人执行验证。

## cli_acceptance：MCP

仅负责 `harness-cli` MCP 包、HarnessConfig.java、ConfigLoader.java、HarnessCli.java、相关 MCP/配置集成测试，以及 docs/MCP.md。另一成员负责 WorkspaceTools 和其测试。保留所有其他修改。

实现可实际使用、可配置的 MCP stdio 客户端：通过 ProcessBuilder 指定可执行文件和参数列表（不隐式使用 shell）；initialize/initialized 握手；有界换行 JSON-RPC 帧；tools/list 和有界分页；tools/call 文本/结构化结果/错误；请求超时、中断与进程清理。若可获得当前官方协议，依据其实现；明确记录支持的协议、传输和能力。不要只为模拟兼容增加外部依赖。不要调用外部服务或模型。

只有显式/可信配置可以启动服务（必须保留现有配置可信策略）。每个外部工具均需要审批，除非可信配置或独立 CLI 启用项明确授权；shell 命令权限不能默默授予 MCP 权限。进程在所选工作区启动，但有宿主权限；不要声称已隔离。注册确定性命名空间工具，保留稳定原始 schema，限制工具输出，提供可关闭生命周期。使用本地夹具服务测试以及 CLI/配置集成测试。通过共享公开接口协调生命周期和注册表 API 改动。不要修改核心、厂商、WorkspaceTools、README、WORKPLAN 或基准脚本。

## responses_acceptance：修改差异

仅负责 WorkspaceTools.java、WorkspaceToolsTest.java、CLI tools 包中新建的变更报告辅助类/测试，以及 docs/CHANGE_REPORT.md。另一成员负责 HarnessCli、配置和 MCP。保留所有其他修改。

为 write/edit 工具修改实现有界、带上下文的统一差异报告，并增加 `changes` 工具显示累计净变更，便于模型报告编辑。在单次注册表生命周期内保留每个编辑路径的基线，正确显示新文件、无操作和已还原编辑。不依赖 Git/shell。保留规范路径约束。限制快照内存和输出；避免在 1 MB 文件上使用二次复杂度 LCS。保持 write/edit 行为和权限保留。有效测试覆盖编辑、新文件、无操作、还原、多差异块、大文件和失败的过期编辑。不要修改核心、厂商、HarnessCli、配置、README、WORKPLAN 或基准脚本。

## 负责人：效率证据与整合

负责基准源码/脚本/文档、根构建/CI/README/WORKPLAN 和整合。构建可复现编码上下文评估，包括质量不变量、可比较基线、估算值/字节/计时指标。区分估算与实际用量、延迟与正确性。真实厂商评估需要明确的任务凭据；本地模拟不能证明真实 API 节省。检查成员文件、整合结果，并串行执行 Maven 验证和打包冒烟脚本。

协作消息不可读取时，在 docs 的任务专用文件中记录简洁状态，并返回明文最终报告。不要覆盖本归属文件或其他负责人文件。
