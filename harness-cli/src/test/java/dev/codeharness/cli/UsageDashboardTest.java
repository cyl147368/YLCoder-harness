package dev.codeharness.cli;

import dev.codeharness.cli.approval.CommandReview;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class UsageDashboardTest {
    @TempDir Path workspace;
    private static final ChatRequest REQUEST = new ChatRequest(List.of(ChatMessage.user("离线夹具")), List.of(), 50);

    @Test void weightedTotalsResetPerTaskButAccumulatePerProcess() {
        var dashboard = new UsageDashboard();
        dashboard.beginTask();
        dashboard.record(response(20, 2, 10, true));
        dashboard.record(response(180, 8, 180, true));
        assertEquals(95.0, dashboard.task().cacheHitRatePercent());
        assertEquals(new TokenUsage(200, 10, 190), dashboard.task().total());
        dashboard.beginTask();
        assertEquals(0, dashboard.task().requests());
        dashboard.record(response(200, 0, 10, true));
        assertEquals(5.0, dashboard.task().cacheHitRatePercent());
        assertEquals(50.0, dashboard.process().cacheHitRatePercent());
        assertEquals(3, dashboard.process().requests());
    }

    @Test void explicitZeroMissingAndPartialCacheAreDistinct() {
        var dashboard = new UsageDashboard();
        dashboard.record(response(20, 0, 0, true));
        assertEquals(0.0, dashboard.task().cacheHitRatePercent());
        assertEquals(1, dashboard.task().outputReportedRequests());
        dashboard.record(response(180, 8, 0, false));
        assertNull(dashboard.task().cacheHitRatePercent());
        assertEquals(0.0, dashboard.task().reportedCacheHitRatePercent());
        assertEquals(1, dashboard.task().cacheReportedRequests());
        dashboard.beginTask();
        dashboard.record(ChatResponse.text("未上报"));
        assertEquals(0, dashboard.task().inputReportedRequests());
        assertEquals(0, dashboard.task().cacheReportedRequests());
        assertNull(dashboard.task().reportedCacheHitRatePercent());
    }

    @Test void zeroDenominatorAndImpossibleCacheHaveNoNormalRate() {
        var dashboard = new UsageDashboard();
        dashboard.record(response(0, 0, 0, true));
        assertNull(dashboard.task().cacheHitRatePercent());
        dashboard.record(response(10, 2, 20, true));
        assertNull(dashboard.task().cacheHitRatePercent());
        assertNull(dashboard.task().reportedCacheHitRatePercent());
    }

    @Test void explicitlyUnreportedNonzeroValuesAreExcludedPerField() {
        var dashboard = new UsageDashboard();
        dashboard.record(response(20, 2, 10, true));
        dashboard.record(new ChatResponse("无效数值", List.of(), new TokenUsage(999, 4, 888), FinishReason.STOP,
                Map.of("usage.inputReported", "false", "usage.outputReported", "true", "usage.cacheReported", "false")));
        dashboard.record(new ChatResponse("所有字段均无效", List.of(), new TokenUsage(100, 100, 100), FinishReason.STOP,
                Map.of("usage.inputReported", "false", "usage.outputReported", "false", "usage.cacheReported", "false")));
        assertEquals(new TokenUsage(20, 6, 10), dashboard.task().total());
        assertEquals(1, dashboard.task().inputReportedRequests());
        assertEquals(2, dashboard.task().outputReportedRequests());
        assertNull(dashboard.task().cacheHitRatePercent());
        assertEquals(50.0, dashboard.task().reportedCacheHitRatePercent());
    }

    @Test void streamingUsesStreamingOverloadAndRecordsOnce() {
        var dashboard = new UsageDashboard();
        AtomicInteger chunks = new AtomicInteger();
        ChatModel model = dashboard.track(new ChatModel() {
            public ChatResponse complete(ChatRequest request) { throw new AssertionError("流式不应退回同步"); }
            public ChatResponse complete(ChatRequest request, Consumer<String> listener) {
                listener.accept("甲"); listener.accept("乙");
                return response(30, 2, 10, true);
            }
        });
        model.complete(REQUEST, text -> chunks.incrementAndGet());
        assertEquals(2, chunks.get());
        assertEquals(1, dashboard.task().requests());
        assertEquals(new TokenUsage(30, 2, 10), dashboard.task().total());
    }

    @Test void failedRequestsRemainUnknownAndDoNotEraseEarlierUsage() {
        var dashboard = new UsageDashboard();
        dashboard.record(response(20, 2, 10, true));
        var model = dashboard.track(request -> { throw new IllegalStateException("离线失败"); });
        assertThrows(IllegalStateException.class, () -> model.complete(REQUEST));
        assertEquals(2, dashboard.task().requests());
        assertEquals(1, dashboard.task().inputReportedRequests());
        assertNull(dashboard.task().cacheHitRatePercent());
        assertEquals(50.0, dashboard.task().reportedCacheHitRatePercent());
    }

    @Test void reviewTimeoutCannotAddLateUsageOrContaminateNextTask() throws Exception {
        var dashboard = new UsageDashboard();
        var returned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reviewer = new CommandReview(request -> {
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { /* 模拟忽略取消的厂商。 */ }
            }
            returned.countDown();
            return response(100, 10, 80, true);
        }, Duration.ofMillis(50), 1, text -> {}, dashboard::record);
        try {
            assertFalse(reviewer.review(new CommandApproval.Request(workspace.toRealPath(), "pwd")).approved());
            assertEquals(1, dashboard.task().requests());
            assertEquals(0, dashboard.task().inputReportedRequests());
            dashboard.beginTask();
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertEquals(0, dashboard.task().requests());
            assertEquals(TokenUsage.ZERO, dashboard.process().total());
            assertEquals(1, dashboard.process().requests());
        } finally { release.countDown(); }
    }

    @Test void explicitlyReportedZeroReviewUsageIsKnown() throws Exception {
        var dashboard = new UsageDashboard();
        var reviewer = new CommandReview(request -> response(0, 0, 0, true), Duration.ofSeconds(1), 1,
                text -> {}, dashboard::record);
        assertTrue(reviewer.review(new CommandApproval.Request(workspace.toRealPath(), "pwd")).approved());
        assertEquals(0, reviewer.stats().unreportedRequests());
        assertEquals(1, dashboard.task().inputReportedRequests());
        assertEquals(1, dashboard.task().requests());
    }

    private static ChatResponse response(long input, long output, long cached, boolean cacheReported) {
        return new ChatResponse("{\"decision\":\"approve\"}", List.of(), new TokenUsage(input, output, cached), FinishReason.STOP,
                Map.of("usage.inputReported", "true", "usage.outputReported", "true", "usage.cacheReported", Boolean.toString(cacheReported)));
    }
}
