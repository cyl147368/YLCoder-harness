# YLCoder-harness 便携发行包

中文版本同时包含 [中英文 token 对比](LANGUAGE_TOKENS.md)、固定双语语料、逐项结果和 `scripts/compare_language_tokens.py`。脚本可直接使用包内 `lib/ylc.jar` 离线复算；此操作需要 Python 3，正常运行 YLCoder-harness 无需 Python。协议字段及历史实验原始 JSON 保留技术原值。

执行 `mvn -B verify` 后运行：

```shell
python scripts/package_distribution.py
python scripts/smoke_distribution.py
```

Windows 验收还会在隐藏的真实控制台中，将初始代码页分别设为 936 和 65001，读取 Unicode 屏幕缓冲区，检查帮助、doctor 和错误帮助的中文与退出码。`python scripts/smoke_windows_console.py` 可单独检查源码启动器；`python scripts/smoke_interactive.py --launcher ylc.cmd` 检查启动器的 UTF-8 管道中文任务、输出、检查点和恢复。前者验证屏幕输出，后者验证管道输入，均不宣称实体键盘输入验收。

打包器读取根 POM 版本及精确对应的 `-all.jar`，生成 `target/distributions/YLCoder-harness-VERSION.zip`、`.tar.gz` 和 `SHA256SUMS`。每个归档包含启动器、安装器、`lib/ylc.jar`、示例配置、根目录 [中文使用手册](../使用手册.md)、安装说明和本地验收证据。打包器采用显式文件清单，不复制工作区、凭据、会话、私有配置或构建日志。示例中的 `api-key` 留空；直接密钥只写在自己的本地配置中。Maven 和 Python 是构建时要求；用户运行解压后的归档只需 Java 21+。

Windows 用户在源码或发行包根目录的 PowerShell 中运行一次 `.\install.ps1`，安装器将运行时复制到 `$env:LOCALAPPDATA\YLCoder-harness` 并把 `bin` 加入用户 PATH，无需管理员权限。缺少用户配置时会从模板创建 `$HOME\.code-harness\config.yaml`，已有配置保留。随后进入任意目标项目目录运行 `ylc`，默认工作区就是调用目录；不用每天进入本工具目录或传仓库相对配置路径。只有显式 `--workspace` / `-w` 才改变工作区，旧 YAML `workspace` 字段不再影响 CLI，`--config` 也不会切换目录。进入后可用 `/status` 核对。通过另一进程安装后，已有终端需要重新打开。源码版在 jar 不存在时先运行 `mvn -B verify`；便携版无需 Maven。

Linux/macOS 可执行 `python3 scripts/install.py`，默认安装到 `~/.local/share/YLCoder-harness`，再自行把其中的 `bin` 加入 PATH。也可以免安装，从目标项目目录使用原启动器的绝对路径。具体步骤见随包安装说明与使用手册；Linux/macOS 安装和运行尚未完成平台验收。

更新后无需重写已有用户配置，低风险命令自动审核默认生效：有限的只读检查经过本地候选规则后，由当前厂商/模型的无工具子 agent 审核，通过后免去手动输入 `y`。其他命令仍需人工确认，MCP 权限保持独立。审核会增加可能计费的模型请求，默认每任务最多 8 次、每次最多等待 10 秒；失败或不确定时回到手动确认，管道模式拒绝。`ylc --no-auto-review` 或可信配置的 `permissions.auto-review: false` 可关闭；显式全部放行的 `--allow-commands` 仍优先。设置和用量说明见随包使用手册。

两种归档格式包含相同的文件字节和逐文件 SHA-256 清单。它们使用排序路径、固定时间戳、规范化所有者/模式，以及不含源文件名的 gzip 头。`SOURCE_DATE_EPOCH` 可覆盖默认的 2000-01-01 UTC 时间戳，且必须在 ZIP 的 1980–2107 范围内。相同输入重新打包可字节级复现；尚未证明 Maven jar 在不同构建间可复现。校验和与可信副本比较时可以检测字节变化；它不是签名。

Windows/POSIX 启动器根据自己的安装位置找到 jar，并保留调用目录。JAVA_HOME 优先于 PATH。ZIP 读取器对可执行位的支持不同；解压丢失权限时可使用 `sh bin/ylc`。冒烟测试验证归档路径/内容/哈希/模式，再解压到含空格路径，并从另一个含空格路径启动，检查 help、厂商发现、离线 doctor、用法退出码和 localhost 写入/读取工作流。本地仅验证宿主平台启动器执行；此处配置的 Linux CI 仍未执行。

`python scripts/smoke_install.py` 单独验证源码安装，发行包冒烟也会对解压包运行同一套独立用户安装验收：不改真实用户 PATH/配置，在隔离环境通过 PATH 调用裸 `ylc`，验证默认用户配置、当前工作区、中文空格路径、显式覆盖、退出码和重复安装保留配置。Windows 使用真实 PowerShell；十个模型请求全部发送给 localhost。便携包结果保存在 `target/distributions/global-install.json`。

随包的 `python scripts/smoke_auto_review.py --launcher bin/ylc.cmd` 可验证实际启动器的无工具审核请求、通过后复用、人工结论、无效响应、服务失败、关闭开关、完整授权优先级及用量合计；全部请求使用临时 localhost 服务，不读取用户配置。POSIX 将启动器改为 `bin/ylc`。发行包验收结果另保存在 `target/distributions/auto-review.json`。

归档是本地快照，不是已发布版本。理解随包证据前，请阅读 WORKPLAN.md 和厂商/基准限制。shaded jar 原有的第三方运行时声明仍保留；本次打包不引入新的项目许可证，也不宣称完成法律发行审查。
