package dev.codeharness.cli;

import dev.codeharness.core.agent.ContextMetrics;

/** 每项用户任务只报告新摘要或新的降级原因；完整指标仍逐请求写入日志。 */
final class ContextNotice {
    private String lastDegradedReason;

    boolean shouldShow(ContextMetrics metrics) {
        boolean newSummary = metrics.summarizedMessages() > 0 && !metrics.reused();
        boolean changedDegradation = metrics.degraded() && !metrics.reason().equals(lastDegradedReason);
        lastDegradedReason = metrics.degraded() ? metrics.reason() : null;
        return newSummary || changedDegradation;
    }
}
