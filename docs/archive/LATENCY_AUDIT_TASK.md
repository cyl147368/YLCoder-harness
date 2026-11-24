# 独立延迟验收审计

负责人：responses_acceptance。仓库夹具已移交给负责人。仅负责 docs/LATENCY_AUDIT.md。其他成员也在工作；不要修改脚本、源码、配置或 WORKPLAN，不要访问真实凭据或控制器，不要调用外部模型或运行 Maven。

根据真实首段文本测量目标，审阅 scripts/measure_latency.py 和 check_latency_measurement.py。确认首次观察到的 CLI 标准输出在进程完成前记录时间；精确回答与无工具校验能拒绝格式错误或未完成的样本；失败样本保留在报告中；对请求流式模式作出限定说明；不会把 CLI 的合计 token 数凭空拆分为各项用量。识别有实际意义的计时、清理、误判通过或脱敏缺口。负责人已增加 createdAt、baseUrl、model，并将厂商超时修正为 min(60, 样本超时)，同时重新运行 localhost 检查。只有确切且未解决的问题需要验证时，才运行现有 localhost 检查；否则源码审阅和已有权威输出已足够。在负责的审计报告中记录发现和限制。
