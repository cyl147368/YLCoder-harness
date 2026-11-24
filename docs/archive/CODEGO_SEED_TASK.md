# 精确预置评估器 localhost 验收

负责人：cli_acceptance。仅负责 scripts/check_seeded_evaluation.py 和 docs/CODEGO_SEED_ACCEPTANCE.md。此前所有源码归属已移交给负责人。其他成员也在工作；不要修改 evaluate_coding.py、validate_custom_provider.py、配置、WORKPLAN 或其他文档。

通过 evaluate_coding.run_case 运行有界本地夹具，设 args.live=True，以准确覆盖基线和紧凑两种预算的预置历史/检查点路径。这是 localhost 结构测试，不是外部模型调用。使用临时、显式配置引用唯一的人工凭据变量；只为受控 localhost 服务设置，结束后删除。不要访问真实凭据或控制器，不要调用外部主机或运行 Maven。

模拟 Chat Completions 服务必须校验角色/工具 ID 的有序配对、保留的 Subject.java 内容、预期 stream/max_tokens，以及首请求计数。对 inclusive-range 返回已知 edit_file 修复，再返回完成信号；不用其他工具。检查 run_case 的独立质量验证，并确认修正后的 retainedToolNamesAfterSeed 字段在检查点压缩后，两种预算均包含 edit_file。本地验证原始预置的当前源码保留以及编译器正负对照。只记录结构指标与结果状态，不能记录完整请求载荷。负责人掌管所有真实验收和打包。
