# 自动审核误判修复与验收

日期：2026-10-29。本次针对两个重复出现的人工确认问题：

1. `git diff --stat` 返回“无法验证程序或目录身份，未调用审核 AI”。
2. 由 Git 日志、差异、状态、echo、tail 和 wc 组成的只读命令链返回“命令不属于已支持的受限查询”。

## 根因

`git diff --stat` 没有显式 pathspec 时，旧实现仍把 `.` 当作路径并递归遍历整个工作区。工作区中的 `.harness` junction、不可访问目录或其他无关特殊项会触发异常，最终被显示成程序或目录身份验证失败。该遍历并不是 Git 差异查询的必要条件。

复合命令虽然以 `git ` 开头，却被旧控制流当成单条 Git 查询；后续 `&&` 和 `|` 因此无法形成受限计划。分类器没有收到任何请求，界面只在本地安全检查失败后要求人工确认。

## 修复

- 无 pathspec 的 `git diff` 只检查规范工作区、普通 `.git`、外部 Git 程序、仓库配置和文件身份，不遍历无关工作区内容。
- 显式 pathspec 只检查目标及尚存父目录，继续拒绝链接、junction、越界和不可访问路径。
- 新增有界只读命令链解析：`git status`、受支持的 `git diff`、无 revision/pathspec 的 `git log --oneline -N`、`echo`、head/tail 尾行限制和 `wc -l`；最多 12 段，只接受 `&&` 与单个 `|`。
- Git 计划继续禁用全局/系统配置、pager、fsmonitor、子模块检查和可选索引锁。复合计划绑定 Git、head/tail/wc、系统 shell 的绝对路径和文件身份，以及仓库配置指纹。
- 复核通过后，固定只读链直接走本地快速路径，不请求审核模型；变量、命令替换、引号、重定向、分号、其他程序、读取文件的 head/tail/wc 和未知组合仍在本地拒绝。
- 条件复核不一致仍会转人工。分类器失败、格式错误、超时或额度耗尽仍不会批准候选操作。

## 验证

最终定向 Java 回归：

```text
Tests run: 167, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

跳过项是当前 Windows 权限下不能创建符号链接的平台用例；其他链接边界由 junction 回归覆盖。日志：`target/auto-review-fix-final-tests.log`。

真实 CLI 进程验收覆盖以下两条命令：

```text
git diff --stat
git log --oneline -5 && echo --- && git diff --stat | tail -20 && echo --- && git status --short | wc -l
```

源码运行包和已安装全局启动器分别通过：

- 2 次主模型请求；
- 0 次审核模型请求；
- 2 次本地放行；
- 0 次 `y/N` 人工确认；
- 两条工具调用均返回 `退出码：0`，输出包含提交日志、`---` 和 `pom.xml`。

证据：`target/readonly-chain-smoke.json`、`target/readonly-chain-global.json`。测试只使用隔离临时仓库和 localhost 模型夹具，不加载真实用户模型配置，也不请求真实厂商。

最终源码与全局 JAR 的 SHA-256 均为：

```text
9260ea17788311f9ee554aa38ffb9991989b5a11b77a38439a538214c13d2ccc
```

全局安装保留现有用户配置。正在运行的旧 `ylc` 进程仍使用已加载的旧代码，退出并重新启动后生效。

## 边界

- 分类器仍无法证明任意脚本无害；因此白名单外命令继续人工确认。
- 自动审核不是操作系统沙箱，获批程序仍拥有当前用户权限。
- 本地快速路径按程序文件和仓库配置身份复核；不保证识别所有文件系统竞态。
- 本次未调用真实厂商，也未验证 Linux/macOS 的完整命令链；跨平台代码有单元测试，实际进程验收在 Windows 完成。
