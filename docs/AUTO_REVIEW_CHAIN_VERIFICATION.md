# 长命令自动审核验收：2026-10-30

本轮处理用户多次反馈的长查询命令仍触发人工确认问题，完成代码、相关回归、打包、全局安装和实际启动器复验。

## 修复行为

- 命令审核入口统一为 4,096 字符，不再保留普通命令的 128 字符分支；长度检查不代替语法与风险判断。
- 本地规则与无工具 AI 分类器共用参数、连接操作符、stderr 重定向的解析结果。执行外部程序的固定路径与重新生成的参数，不直接运行原始模型命令文本。
- 常见 ls/Git/head/tail/wc 命令链逐段校验后本地放行；ls 保留选项及目录，目录通配符显式展开，wc 支持工作区普通文件和中文空格文件名。
- ls/wc 操作数及祖先路径检查链接、junction、设备、敏感名称和工作区边界；程序与配置身份在执行前复核，文件变化使批准失效。
- Git 配置内容进入指纹。精确的 git branch --show-current 不因 clean/process 过滤器配置而误拦，其他配置检查仍生效；可能触发过滤器的查询保留拦截。

## 验证结果

| 验证 | 结果 |
|---|---|
| 相关回归 | 273 项，271 项通过，2 项平台条件跳过；零失败、零错误 |
| 离线 Maven package | 成功 |
| 源码 JAR 的真实 CLI | 7 条工具命令完成；6 次本地放行、1 次 AI 审核、0 次人工确认 |
| 全局 ylc.cmd 的真实 CLI | 同一组 7 条工具命令完成；6 次本地放行、1 次 AI 审核、0 次人工确认 |
| 每组模拟模型调用 | 2 次主请求、1 次审核请求；全部 localhost |
| 项目外全局入口 | 加载用户 PATH 后，ylc 解析到用户级安装目录，--help 成功 |
| 包一致性 | 源码、安装包及两组冒烟证据的 SHA-256 相同 |
| 必需类 | ReviewableShell、ShellPathValidation、UnifiedDiff 均在发行 JAR 内 |
| 补丁格式 | 相关已跟踪文件 git diff --check 通过；新增文件无行尾空白 |

SHA-256：

```text
1da25e6e86c72724a6feca247dcde4171d104e7d37fc0b058ea628ca4a7c5edd
```

7 条命令包含单个 git diff、Git 状态/日志/差异汇总、目录通配符与 stderr 丢弃、原生 rg/sed 前缀裁剪、由分类器审核的 git show、Git/rg 混合链，以及用户反馈的 ls/wc/目录列表/stderr 合并/head 长命令。完整命令与结果记录在以下项目文件：

- target/structured-review-regression.log
- target/structured-review-package.log
- target/structured-review-source.json
- target/structured-review-global.json
- target/structured-review-acceptance.json

## 使用与边界

用户级安装已更新，现有私有配置保留，bin 目录已加入用户 PATH。旧 ylc JVM 退出并重启才会使用新运行包；其他已打开的终端需重新打开以加载 PATH。在任意目标项目目录执行 ylc --approval-mode review，默认工作区就是当前目录；会话中可用 /approval review，输入框可用 Shift+Tab 或 Alt+M 切换模式。

本轮采用参考项目的语法、权限、安全检查、无工具分类器分层思想，没有移植其完整 Bash AST。当前仍是有界解析器，变量/命令展开、||、文件输入/写入重定向等不支持；未知参数、无法验证的路径、审核不可用或执行条件变化仍转人工。组合 rg 文件清单借助 Git ls-files，与独立原生 rg 的忽略规则不同。路径检查不是操作系统沙箱，不能消除全部文件系统竞态；无 pathspec 的 git diff 在审批阶段不逐一扫描受跟踪文件，而实际未暂存差异仍读取这些文件。

本轮没有重复全量 verify，没有读取用户私有配置正文，没有调用真实厂商，也未提交或推送。localhost 结果不能证明真实审核模型的准确率、费用和延迟。
