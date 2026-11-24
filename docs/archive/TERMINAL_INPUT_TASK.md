# 共享输入验收

cli_acceptance 仅负责新建 harness-cli/src/test/java/dev/codeharness/cli/TerminalInputWorkflowTest.java。负责人负责生产代码。其他成员也在工作，请保留他们的修改。不要运行 Maven 或真实调用。阅读 AGENTS.md。

负责人引入包私有 TerminalInput 接口，提供 `String readLine() throws java.io.IOException` 和 `boolean isTerminal()`。HarnessCli 增加包私有 HarnessCli(TerminalInput input) 构造器，保留现有 public 无参构造器。测试可以创建 new CommandLine(new HarnessCli(fake))，并配置 out/err writer。模拟共享读取器应依次提供任务、审批回答、下一任务等。审批使用相同输入，在消耗回答前检查 isTerminal=false；EOF 拒绝；仅接受 yes/y（不区分大小写）。IOException 导致安全的 CLI 失败，不执行命令。Shell/MCP 的显式启用仍相互独立。

编写真实进程内 CLI 验收，使用 localhost 模拟厂商和临时工作区，不用凭据。两个交互任务：第一个请求 run_command 执行无害的 `echo terminal-approved > approved.txt`，模拟器验证命令成功；第二个返回最终回答。输入顺序为第一任务 / y / 第二任务 / exit。验证厂商用户消息只包含任务，且只执行一次修改性命令。isTerminal=false 的管道模拟必须拒绝 run_command，且不能把下一任务消耗为审批回答。可增加拒绝/EOF 情况。使用显式 {} 配置、--no-stream 减少夹具复杂性，并使用正确的厂商结束标记。仅测试新文件；负责人串行运行 Maven。最终使用明文报告。
