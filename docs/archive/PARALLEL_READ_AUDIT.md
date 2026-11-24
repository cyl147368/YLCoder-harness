# 并行读取审计

负责人处理结果：新增 `resultCallbacksCancelBeforeFollowingMutationBarrier`，覆盖一个/两个读取之后的两种回调。完整 reactor 于 2026-10-14 11:35:44 Asia/Shanghai 通过。原始发现保留为审阅历史。

## 结果

本只读审计未建立其他具体生产缺陷。当前调度器缺少验收测试：完成的安全读取批次和下一不安全工具之间，工具进度回调触发取消。`executeReadBatch` 通过 `onCheckpoint`、`onToolResult` 发布每个结果，然后在批次结束检查取消供应器。最后读取的 `onToolResult` 可设置取消标记；实现在进入修改屏障前检查该标记，但当时此边界未被测试。

这是回归测试机会，不是复现正确性失败。工具回调由用户提供，可改变取消状态；失去边界检查可能在取消后启动写入或命令。

## 复现/测试建议

增加确定性、不用 latch 的测试：调用 `[safe_read, mutate]`，`maxParallelReads=2`。安全读取正常返回；该读取的 `AgentListener.onToolResult` 设置 `AtomicBoolean` 取消标记。修改工具递增 `AtomicInteger`，应从不执行。断言 `AgentResult.Status.CANCELLED`、一次模型请求、零修改、转录一个工具结果，以及没有修改 `onToolStart` 回调。再用同批两个安全读取，在第二个（最后）结果设标记，覆盖有序结果发布。

同样断言最后读取结果后的 `onCheckpoint` 设置取消也阻止屏障。不用 sleep 或耗时阈值。测试放在已有并行读取验收套件；本审计不建议生产改动。

## 范围和证据

只读审计 `harness-core/src/main/java/dev/codeharness/core/agent/CodeAgent.java` 和八项 `ParallelReadsTest`。已有实现在 `executeReadBatch` 返回时做预期取消检查；当前套件覆盖读取阻塞期间取消、检查点失败及不协作清理。缺口是上述回调到屏障的边界。未使用 Maven、网络或真实模型调用。
