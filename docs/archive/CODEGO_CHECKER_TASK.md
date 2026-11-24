# 独立验收指派（历史记录）

最近状态：验证器修复已整合并交回。当前有界、仅localhost任务参见 docs/CODEGO_SEED_TASK.md。

## 后续归属移交

只读审计已完成。现仅负责 scripts/validate_custom_provider.py 和 docs/CODEGO_CHECKER_ACCEPTANCE.md。修复具体发现：序列化前递归精确密钥脱敏、成功read/edit/read有序工具结果证据、工作区允许列表（Answer.java + task.yaml + .harness运行状态）及用量可用性限定。保留真实调用边界和独立编译。要求成功edit_file结果，不允许write_file/run_command/MCP操作，在编辑前后读取Answer.java。解析前检查实际持久化TOOL内容结构，不能虚构字段。为失败编辑、错误顺序、未允许文件及转义人工凭据增加有效离线合成负对照。有需要可负责新 scripts/check_custom_validation.py。不要访问网络、真实密钥、控制器或运行Maven。其他成员也在工作；适应负责人对evaluate_coding.py、配置、文档的修改。记录精确离线验证。真实重跑仍由负责人负责。

负责人：cli_acceptance。总负责人掌管所有真实网络请求、配置、验证脚本修改、WORKPLAN和交付文档。其他成员也在代码库工作，不回退或覆盖他们的修改。

阅读项目AGENTS.md。审阅 scripts/validate_custom_provider.py、scripts/evaluate_coding.py 的真实验收误判或凭据报告缺陷。不要访问凭据或控制器进程，不网络调用、运行Maven或改源码/脚本。仅负责 docs/CODEGO_CHECKER_ACCEPTANCE.md。离线运行 evaluate_coding.py --task inclusive-range --repeat 1，简洁记录对照/配对结果和审阅发现。使用cmd.exe、login:false。全部命令限于项目或脚本自己的临时夹具。
