# 网关失败路径只读追踪

负责人：responses_acceptance。其他成员也在此代码库工作。仅负责 docs/CODEGO_FAILURE_TRACE.md。不要修改源码、访问凭据或网络、运行 Maven，或修改其他成员的文件。

小型真实工作流在两种模式下均已通过，但 evaluate_coding.py 的真实配对样本以 iterations=0 失败。阅读 scripts/evaluate_coding.py、当前 CLI 的 JSON 结果结构，以及原生厂商适配器首次请求的校验路径。确定哪些本地或厂商错误可能在迭代次数增加之前发生，以及负责人应保留哪些安全的诊断信息。依据当前 SessionStore 和请求编码，检查预置检查点、角色、工具 ID 和历史记录是否合法。不要猜测网关损坏。追踪准确的相关代码位置，并报告可采取行动的发现。负责人正在为评估器增加脱敏错误保留功能，并负责所有真实调用。
