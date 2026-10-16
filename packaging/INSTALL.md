# YLCoder-harness 便携 CLI 安装

将完整归档解压到任意目录。必须安装 Java 21 或更高版本；编译 Java 项目需要 JDK。运行时依赖已包含在 `lib/ylc.jar` 中。运行此发行包不需要 Maven 或 Python。

启动命令为 `ylc`。用户配置 `~/.code-harness/config.yaml` 和工作区 `.harness` 会话目录保持兼容；更新安装不会要求迁移已有密钥或会话。

## Windows：安装一次，任意目录使用

在完整解压后的包根目录打开 PowerShell，执行：

```powershell
.\install.ps1
notepad "$HOME\.code-harness\config.yaml"
```

安装器把运行时复制到 `$env:LOCALAPPDATA\YLCoder-harness`，将 `bin` 加入当前用户 PATH，不需要管理员权限。用户配置只在缺少时从模板创建，已有配置不会覆盖。重新运行安装器可更新运行时。安装脚本在当前 PowerShell 中执行时会刷新本终端 PATH；通过另一个 PowerShell 进程安装时，完成后重新打开终端。如果脚本被执行策略阻止，可先在本终端执行 `Set-ExecutionPolicy -Scope Process Bypass`。

在配置中填写厂商的 API 基础地址 `base-url`、模型 ID `model` 和密钥 `api-key`，无需设置环境变量。模板使用 `openai-compatible`，使用其他协议时调整 `provider`。工作区无需配置：在哪个目录执行 `ylc`，默认就处理哪个目录。旧 YAML 的 `workspace` 字段保留解析兼容，但 CLI 忽略其值。之后在任意项目目录执行：

```powershell
cd "E:\你的项目目录"
ylc
ylc run "修复解析器并验证结果"
ylc resume SESSION_ID "同时处理空输入"
```

不需要再进入安装目录，也不必每次传 `--config` 或 `--workspace`。进入后输入 `/status` 可核对实际工作区；只有命令行显式传入 `--workspace`（或 `-w`）才会改用其他目录。运行 `ylc doctor` 和 `ylc providers` 可检查本地设置，不会联系模型。完整步骤见根目录的 [中文使用手册](使用手册.md)。

环境变量仍可选：将 `api-key` 留空，再设置 `api-key-env` 指定的变量。显式 `--api-key-env` 优先于配置密钥；其余情况由非空 `api-key` 优先。变量名的下划线不需要反斜杠转义。CodeGo 配置仅用于复现历史验证。用户配置包含明文密钥，请勿分享或提交。

默认启用低风险命令自动审核，不必修改旧配置：有限的只读检查先经过本地规则，再由当前厂商/模型的独立无工具子 agent 审核，通过后无需输入 `y`。删除、安装、联网、解释器、构建/测试脚本和复杂 shell 仍会询问；审核失败、超时或不确定时也回到手动确认，管道模式直接拒绝，MCP 审批独立。每次审核也是可能计费的模型请求，默认每任务最多 8 次、每次最多等待 10 秒。临时关闭可运行 `ylc --no-auto-review`，长期关闭在用户配置设置 `permissions.auto-review: false`。不要重复创建 `permissions` 段；其他限制与统计见使用手册。显式 `--allow-commands` 会预先放行全部命令，并优先于这个开关。

Windows 启动器自动将当前控制台和 Java 标准输出/错误输出统一为 UTF-8，解决中文乱码；不修改系统区域设置。旧终端已显示的乱码不会自动重绘，重新执行命令即可看到修复后的输出。

## 其他配置、免安装和 Linux/macOS

可从任意目录指定另一个配置或工作区：

```powershell
ylc --config "C:\我的配置\config.yaml" --workspace "E:\其他项目"
```

`--config` 只选择配置，不改变工作区。相对路径以调用目录为准，不以安装目录或配置所在目录为准。用户配置默认从 `~/.code-harness/config.yaml` 加载；多份配置不会逐层合并。

免安装用户可在目标项目目录通过完整路径调用启动器：Windows 为 `& "C:\工具\YLCoder-harness\bin\ylc.cmd"`，Linux/macOS 为 `sh /path/to/distribution/bin/ylc`。设置 JAVA_HOME 时，启动器使用 `JAVA_HOME/bin/java`，否则使用 PATH 中的 java。请保持解压包的 bin/lib 布局完整；启动器不会改变当前目录。免安装方式不会自动创建用户配置，可自行从 `config.example.yaml` 复制，或显式选择配置文件。

Linux/macOS 可用 Python 3 执行一次：

```sh
python3 scripts/install.py
export PATH="$HOME/.local/share/YLCoder-harness/bin:$PATH"
```

请将 PATH 设置加入所用 shell 的启动文件，使新终端也能找到命令。该安装器默认将运行时放入 `~/.local/share/YLCoder-harness`，只在缺少时创建用户配置；正常运行不需要 Python。Linux/macOS 安装和运行尚未完成平台验收。

包内不包含工作区、凭据或会话。自定义厂商 jar 可配合 `java -cp` 和 `dev.codeharness.cli.HarnessCli` 使用，见 README.md。

`manifest.json` 记录包内文件的 SHA-256。外部 SHA256SUMS 记录各归档的校验和；两者都不是签名，也不能证明发布者身份。构建/验收证据和已知限制见 WORKPLAN.md。此发行包是本地快照；最初的快照验收未验证真实厂商或 Linux，后续 CodeGo 实测见 docs/CODEGO_EVALUATION.md，Linux 运行验收仍未完成。
