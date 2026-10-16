# 验证记录与已知限制

本项目提供 Java 21 编程 CLI。公开仓库保存源码、测试、中文文档与固定公开基准数据；本地配置、密钥、会话、构建产物和团队任务记录不进入仓库。

## 最近一次本机验证

2026-10-16，在 Windows 上执行 `mvn -B verify`：发现 377 项测试，370 项通过，7 项平台条件跳过，零失败、零错误。此次验证包括多厂商协议适配、工作区工具、恢复、上下文预算、自动审核、用量统计及底部状态栏。

独立 localhost 验收覆盖下列内容，不依赖真实厂商密钥：

- 终端用量的 9 个场景、15 次请求，区分未报告、明确零与部分报告；缓存率按已报告输入加权。
- Windows 真实控制台：72 行及 400 行回答、300 行滚动缓冲区、第二任务审批及正文保留、退出恢复；400 行 SSE 输出中途暂停时核对正文末六行和四行底栏。
- 源码、用户安装版及解压后的便携包，包含中文输入、流式输出、工具、审核、会话和配置。
- 同一构建产物和公开输入重复打包，ZIP/tar.gz 字节一致，清单及独立 SHA-256 核对通过。

## 从源码复验

需要 JDK 21、Maven 3.9+，运行脚本时需要 Python 3。下列命令在仓库根目录执行：

```shell
mvn -B verify
python scripts/smoke_cli.py
python scripts/smoke_dashboard.py
python scripts/smoke_interactive.py
python scripts/smoke_bottom_status.py
python scripts/smoke_bottom_status.py --streaming
python scripts/package_distribution.py
python scripts/smoke_distribution.py
```

完整本地验收集合见 [CI 配置](../.github/workflows/verify.yml)。CI 中配置了 Windows 和 Ubuntu；本机记录不能代替远端 CI 的实际结果。

2026-10-16，Java 21 的 Ubuntu 完整离线 CI 已通过，覆盖模块测试、模拟厂商、上下文评测、便携包、安装、外部 SPI 和交互恢复，见[该次运行](https://github.com/cyl147368/YLCoder-harness/actions/runs/38887458062)。该记录只说明此运行的 Ubuntu 作业结果；其他平台以[当前 CI](https://github.com/cyl147368/YLCoder-harness/actions)为准，macOS 尚未验收。

## 限制

本轮没有调用真实厂商，协议支持不代表每家服务和每个模型组合都经过线上验证。历史自定义网关实测见 [CodeGo 评测](CODEGO_EVALUATION.md)，其时间、模型和样本范围不能推广为通用速度或费用承诺。

固定底栏在 Windows 控制台验证；Linux/macOS 使用普通文本状态区。窗口调整后在下一个输出事件刷新。未覆盖所有宿主窗口重排、逐字符流式分片或实体输入法；缩放及退出后可能在滚动历史保留旧状态文字。

英文系统区域的 Windows（ACP1252）搭配 Java 21 时，Java 原生启动器会在进入应用前把无法表示的中文命令行参数转换为问号。`chcp 65001` 和标准输出 UTF-8 设置不能修复这个参数问题。该环境应使用 ASCII 安装路径，进入目标目录后运行 `ylc`，在交互界面输入中文任务；不要把原生中文命令行参数视为已验证支持。含中文配置/工作区路径的离线验收通过应用级 UTF-8 参数文件传递，不代表 Java 原生启动器的限制已经消失。

基准数据分别说明真实调用、localhost 模拟与合成测量的口径，见 [基准说明](BENCHMARKS.md) 和 [中文/英文 token 对比](LANGUAGE_TOKENS.md)。最初十小时工程窗口已经结束，没有测得十小时持续计算，也没有恢复旧自动化。模型完成任务不等于代码已验证，请自行运行项目测试。
