package dev.codeharness.cli;

import dev.codeharness.core.agent.ContextMetrics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextNoticeTest {
    private ContextMetrics metrics(boolean reused, boolean degraded, String reason) {
        return new ContextMetrics(32000, 5000, 10000, 20, reused, degraded, 1, reason);
    }
    @Test void reusedSummaryDoesNotPrintEveryModelIteration() {
        var notice = new ContextNotice();
        assertTrue(notice.shouldShow(metrics(false, false, "已整理")));
        assertFalse(notice.shouldShow(metrics(true, false, "复用已保存摘要")));
        assertFalse(notice.shouldShow(metrics(true, false, "复用已保存摘要")));
        assertTrue(notice.shouldShow(metrics(false, false, "已整理")));
    }
    @Test void reportsDegradationChangesAndRecoveryWithoutFlooding() {
        var notice = new ContextNotice();
        assertTrue(notice.shouldShow(metrics(true, true, "本地裁剪")));
        assertFalse(notice.shouldShow(metrics(true, true, "本地裁剪")));
        assertTrue(notice.shouldShow(metrics(true, true, "摘要失败")));
        assertFalse(notice.shouldShow(metrics(true, false, "恢复")));
        assertTrue(notice.shouldShow(metrics(true, true, "摘要失败")));
    }
}
