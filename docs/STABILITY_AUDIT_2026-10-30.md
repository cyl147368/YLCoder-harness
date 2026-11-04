# 稳定性扫描与修复验收：2026-10-30

本轮按用户要求扫描命令审核、会话恢复、模型选择、流式重试、交互输入和 MCP 子进程链路，对照本机 claude-code-haha 源码。先增加可复现问题的测试，再修改实现，最后运行完整离线 verify 和打包后实际入口。

## 已修复的问题

| 问题 | 修复后的行为 | 对照参考项目或复用点 |
|---|---|---|
| /model 收到响应头后，厂商正文停滞会无限等待 | 模型目录请求的总等待覆盖正文读取，最多 5 秒；读取中限制为 1 MiB，超时或超限取消请求，保留离线模型候选 | 参考 withRetry 的取消与截止时间思想；具体有界响应订阅复用本项目 PublicWebClient 的处理方式 |
| HTTP 429/503 等暂时失败未进入重试；兼容降级中认证错误还会浪费重试 | 408/429/500/502/503/504 保持当前协议及流模式，按 1/2/4 秒重试；与兼容降级共用最多 3 次预算；401/403 等明确失败立即停止；已显示文本不重试 | [withRetry.ts](../../claude-code-haha/src/services/api/withRetry.ts) 的错误分类、取消和集中预算；本项目保留用户指定的 1/2/4 秒策略 |
| find 的当前目录 . 被尾随点规则拒绝，目录操作数又被误当作普通文件 | 可解析目录查询通过路径检查后交给无工具分类器；按目录语义绑定执行目标。继续拒绝 -L/-H/-follow、删除/执行动作和敏感/越界路径 | [BashTool/pathValidation.ts](../../claude-code-haha/src/tools/BashTool/pathValidation.ts)、[permissions/pathValidation.ts](../../claude-code-haha/src/utils/permissions/pathValidation.ts) 的路径操作语义，以及 [permissions.ts](../../claude-code-haha/src/utils/permissions/permissions.ts) 的分层决策 |
| 列表先截取 20 个检查点再排除损坏文件，导致有效旧会话被遮住 | 扫描最近最多 200 个候选，返回最多 20 个有效会话，损坏文件不占展示名额；保留 /resume 序号 | [sessionStorage.ts](../../claude-code-haha/src/utils/sessionStorage.ts) 的候选与加载结果分离；200 个扫描上限是本项目的有界实现 |

同时修正了一个过时的测试断言：安全的 ls/echo 组合不应因存在连接符就被判为危险；负例改为实际写入重定向，继续验证未批准动作不会执行。Windows 的 find 查询只绑定类 Unix 工具，不把系统同名的文本筛选器当作替代。

参考项目的完整 Bash AST、全部权限规则、模型专用恢复逻辑与会话渐进界面没有整体移植。本轮是对照相关模块修复已复现的问题，不代表与参考项目全部行为一致。

## 验证

新增测试先在旧实现上复现：模型目录正文超时、暂时 HTTP 失败、目录查询误拦和损坏检查点遮挡有效会话。证据：target/audit-reproduced-tests.log。

完整离线 Maven verify 通过：

| 模块 | 测试总数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| harness-core | 51 | 0 | 0 | 0 |
| harness-providers | 132 | 0 | 0 | 0 |
| harness-cli | 671 | 0 | 0 | 14 |
| 合计 | 854 | 0 | 0 | 14 |

840 项实际执行通过，14 项按条件跳过。日志为 target/audit-final-verify.log。初次沙箱内验收的 MCP/子进程测试受运行环境影响；沙箱外使用当前用户缓存离线复查后通过，没有为掩盖环境失败改动这些业务实现。

打包后的源码 JAR 和实际全局 ylc.cmd 各通过以下验收：

- Chat、Responses × 流式、非流式：4 个 HTTP 429→503→成功场景，每个场景真实请求 3 次；保持原协议、模型、消息、工具定义和模式，使用同一会话。
- 全局流式 Chat 退避实测 1.066/2.038 秒，Responses 1.052/2.041 秒；非流式 Chat 1.065/2.042 秒，Responses 1.071/2.036 秒。三次完整预算、混合兼容降级、认证失败和部分文本边界由 JUnit 回归覆盖。
- 之前用户报告的 7 条只读命令：6 次本地放行、1 次分类器批准、0 次人工确认。
- 源码和全局 JAR 的 SHA-256 一致：a7829df3f05fd3d315f2938d3a6339e2fc6f6983d597f2fe49d5812365e0097b。
- git diff --check 通过。

统一机器可读证据：target/audit-acceptance.json。独立进程证据为 target/audit-{source,global}-http-{stream,nonstream}.json 和 target/audit-{source,global}-readonly.json。

## 使用与限制

全局安装已更新，已有私有配置保留。退出旧 ylc JVM，再重新运行 ylc 即可加载新运行包；需要显式启用审核时使用 ylc --approval-mode review。

agent.stream-fallback: false 仅关闭自动兼容降级，不关闭暂时性 HTTP 重试。命令解析仍有界；未支持的语法、风险操作、无法绑定的路径或审核失败仍要求人工确认。find 目录查询会调用审核模型，不等于全部目录命令本地免审核。会话列表最多扫描 200 个候选，并非无限读取所有历史。

所有模型请求均为 localhost 夹具，未读取用户私有配置正文，未调用真实厂商。测试结果不证明真实厂商服务可用性、分类准确率或线上费用。没有提交或推送代码。
