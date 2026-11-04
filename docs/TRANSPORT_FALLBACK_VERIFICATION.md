# 网络断流诊断与重试验收

验收日期：2026-10-27。汇总证据：`target/transport-fallback-acceptance.json`。

## 故障与修复

用户看到“流式请求失败”后立即结束，没有显示网络原因或进入已有重试。旧的全局版本在 localhost 截断 HTTP 正文的同类场景中也只发起 1 次请求并报出该错误。原因是原生传输将工作线程抛出的连接异常替换成通用错误，丢失了诊断信息和 CLI 用于判断能否降级的标志；总时限超出也缺少该标志。

新增 TransportErrors，按有界异常类型链识别连接拒绝、DNS、超时、正文提前关闭等原因，并为可恢复传输失败保留降级标志。只输出固定中文说明和异常类型，不传播原始异常中的地址、请求头、载荷或凭据。Chat 和 Responses 共用诊断，普通非流式请求也显示诊断。

继续使用已有 CLI 重试机制：尚未输出文本时，最多改用非流式重试 3 次，分别等待 1、2、4 秒；Responses 保持 /responses，Chat 保持 /chat/completions。请求消息、工具定义和模型不变；每次真实请求都计数。不重放已完成工具，不执行不完整工具片段。已输出文字时不自动重试，取消和关闭降级配置有效。初始 TLS/证书、无效配置、未知内部错误等不启动降级；一旦进入降级，后续请求失败继续消耗剩余重试预算。

## 验证结果

- 相关 Java 测试：95 项全部通过，0 失败、0 错误、0 跳过；其中 provider 37 项，CLI 58 项。覆盖真实 HTTP 正文截断、端口拒绝、超时、脱敏、取消、第三次恢复、次数耗尽、JSON 输出、用量记账、会话和工具不重复。日志：`target/transport-cli-tests.log`。
- 完成离线 Maven 打包；本轮测试通过后使用 `-DskipTests package` 生成新运行包。日志：`target/transport-package.log`。本轮没有声称重新完成全量 verify。
- 实际源码 JAR：8 个场景全部通过，22 次 localhost 请求。
- 实际全局启动器：8 个场景全部通过，22 次 localhost 请求。两轮都覆盖 Chat / Responses 的首次恢复、第三次恢复、耗尽重试和部分文本不重试，检查同一请求、同一会话、保存状态、统计与凭据隔离。

| 全局安装版场景 | 请求数 | 实测相邻请求间隔（秒） | 保存状态 |
| --- | --- | --- | --- |
| chat-recover | 2 | 1.022 | COMPLETED |
| chat-third-retry | 4 | 1.032 / 2.009 / 4.02 | COMPLETED |
| chat-exhausted | 4 | 1.02 / 2.018 / 4.009 | FAILED |
| chat-partial-text | 1 | 没有重试 | FAILED |
| responses-recover | 2 | 1.024 | COMPLETED |
| responses-third-retry | 4 | 1.021 / 2.013 / 4.017 | COMPLETED |
| responses-exhausted | 4 | 1.026 / 2.016 / 4.011 | FAILED |
| responses-partial-text | 1 | 没有重试 | FAILED |

次数耗尽时的实际诊断示例：

```text
流式失败后已自动改用非流式重试 3 次，仍未成功；最近错误：openai-responses 请求失败：连接提前关闭，未收到完整响应；请检查网络、代理或厂商服务状态（EOFException）
```

## 安装与复验

已运行 `python -X utf8 scripts/install.py`，全局入口为 `C:\Users\Admin\AppData\Local\YLCoder-harness\bin\ylc.cmd`。安装器保留已有配置；本轮未读取用户私有配置。运行中的旧 JVM 需退出并重新执行 `ylc` 才加载新版。

源码与全局运行包 SHA-256 相同：`35d9256c25ec4e208cb3d416531f1c6babaedd206f1866487449cd09287f334f`。已确认包内包含 TransportErrors、NativeStreamTransport、OpenAiResponsesProvider 和 UnifiedDiff。

可重复运行：

```text
python -X utf8 scripts/smoke_transport_fallback.py --result target/transport-fallback-source-smoke.json
python -X utf8 scripts/smoke_transport_fallback.py --launcher C:/Users/Admin/AppData/Local/YLCoder-harness/bin/ylc.cmd --result target/transport-fallback-global-smoke.json
```

脚本使用临时工作区、独立用户 home、显式 localhost 配置和模拟凭据，不执行模型工具，不调用真实厂商。用户原始日志无法确定远端为何断连；本轮验证的是错误呈现和恢复逻辑，不能据此断言真实网络或厂商问题已消失。已有工作区修改保留，本轮未提交或推送。
