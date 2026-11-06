package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {
    private final ContextCompactor counter = new ContextCompactor();
    private static ContextSummary summary() {
        return new ContextSummary(List.of("实现登录"), List.of("禁止修改数据库；必须保留审计"), List.of("使用现有接口"),
                List.of("检查完成"), List.of("补充测试"), List.of("没有连接凭据"), List.of("src/Auth.java"));
    }
    private static List<ChatMessage> history() {
        List<ChatMessage> h = new ArrayList<>(List.of(ChatMessage.system("系统规则"), ChatMessage.user("实现登录")));
        h.add(ChatMessage.assistant("背景资料".repeat(500)));
        for (int i = 0; i < 8; i++) {
            h.add(ChatMessage.user(i == 3 ? "禁止修改数据库；必须保留审计" : "第" + i + "轮"));
            h.add(ChatMessage.assistant("已检查文件".repeat(500)));
        }
        h.add(ChatMessage.user("继续并测试"));
        return List.copyOf(h);
    }
    private ContextManager manager(ContextSummarizer summarizer) {
        return new ContextManager(counter, new ContextPolicy(true, .85, .65, 2, 1500), summarizer);
    }
    @Test void preservesRequirementsAndRecentOriginalWithoutMutatingTranscript() {
        var h = history(); var copy = List.copyOf(h); var seen = new ArrayList<ChatMessage>();
        var m = manager((original, max) -> { seen.addAll(original); return new ContextSummarizer.Result(summary(), new TokenUsage(20, 10, 0)); });
        var result = m.project(h, 6000, 10000);
        assertEquals(copy, h); assertNotNull(result.snapshot()); assertFalse(result.metrics().degraded());
        assertTrue(seen.stream().anyMatch(x -> x.content().contains("禁止修改数据库")));
        assertTrue(result.messages().stream().anyMatch(x -> x.content().contains("必须保留审计")));
        assertEquals(h.subList(result.snapshot().boundary(), h.size()), result.messages().subList(result.messages().size() - (h.size() - result.snapshot().boundary()), result.messages().size()));
        assertTrue(counter.estimateTokens(result.messages()) <= 6000 * .65);
        assertEquals(new TokenUsage(20, 10, 0), result.summaryUsage());
    }
    @Test void persistsReusesAndInvalidatesRewindButRefreshesSystemRules() {
        AtomicInteger calls = new AtomicInteger(); var h = history();
        ContextSummarizer summarizer = (o, n) -> { calls.incrementAndGet(); return new ContextSummarizer.Result(summary(), TokenUsage.ZERO); };
        var first = manager(summarizer).project(h, 6000, 10000);
        var resumed = manager(summarizer); resumed.restore(first.snapshot());
        List<ChatMessage> next = new ArrayList<>(h); next.set(0, ChatMessage.system("更新系统指引"));
        next.add(ChatMessage.assistant("继续")); next.add(ChatMessage.user("下一步"));
        var result = resumed.project(next, 6000, 10000);
        assertTrue(result.metrics().reused()); assertEquals(1, calls.get());
        assertEquals("更新系统指引", result.messages().getFirst().content());
        next.set(4, ChatMessage.user("回退后换一个需求"));
        assertFalse(resumed.project(next, 6000, 10000).metrics().reused());
    }
    @Test void failedSummaryPreservesOriginalAndAccountsCostWithCooldown() {
        AtomicInteger calls = new AtomicInteger(); var h = history();
        var m = manager((o, n) -> { calls.incrementAndGet(); throw new ContextSummarizer.Failure(new TokenUsage(31, 12, 0)); });
        var first = m.project(h, 6000, 10000);
        assertTrue(first.metrics().degraded()); assertNull(first.snapshot()); assertEquals(new TokenUsage(31, 12, 0), first.summaryUsage());
        m.project(h, 6000, 10000); assertEquals(1, calls.get()); assertEquals(history(), h);
    }
    @Test void keepsOpaqueEvidenceAndToolPairsAtomic() {
        var h = new ArrayList<>(history()); var call = new ToolCall("id", "read_file", "{}");
        h.add(ChatMessage.assistantToolCalls("", List.of(call), Map.of("encrypted", "evidence")));
        h.add(ChatMessage.toolResult(call, "结果")); h.add(ChatMessage.user("继续"));
        var result = manager((o, n) -> new ContextSummarizer.Result(summary(), TokenUsage.ZERO)).project(h, 18000, 10000);
        ToolHistory.validate(result.messages(), false);
        assertTrue(result.messages().contains(h.get(h.size() - 3)));
        assertTrue(result.messages().contains(h.get(h.size() - 2)));
    }
    @Test void recentSystemMessageIsNotDuplicated() {
        var h = new ArrayList<>(history()); h.add(ChatMessage.system("后追加规则")); h.add(ChatMessage.user("继续"));
        var result = manager((o, n) -> new ContextSummarizer.Result(summary(), TokenUsage.ZERO)).project(h, 6000, 10000);
        assertEquals(1, result.messages().stream().filter(m -> m.content().equals("后追加规则")).count());
    }
    @Test void restoredSnapshotCannotCutToolBlockOrRemoveOpaqueEvidence() {
        var call = new ToolCall("call", "read_file", "{}");
        var history = List.of(ChatMessage.system("规则"), ChatMessage.user("任务"),
                ChatMessage.assistantToolCalls("", List.of(call)), ChatMessage.toolResult(call, "结果"), ChatMessage.user("继续"));
        assertFalse(ContextManager.isSummaryBoundaryAllowed(history, 3));
        assertTrue(ContextManager.isSummaryBoundaryAllowed(history, 4));
        var invalid = new ContextSnapshot(1, 3, ContextManager.fingerprint(history.subList(0, 3)), summary());
        var manager = manager(null); manager.restore(invalid);
        var projection = manager.project(history, 6000, 1000);
        assertNull(projection.snapshot());
        assertEquals(history, projection.messages());
        var opaque = List.of(ChatMessage.user("任务"), ChatMessage.assistant("", Map.of("reasoning", "opaque")), ChatMessage.user("继续"));
        assertFalse(ContextManager.isSummaryBoundaryAllowed(opaque, 2));
        assertFalse(ContextManager.isSummaryBoundaryAllowed(history, 0));
    }

    private static List<ChatMessage> fewTurnInspection(int userTurns, int cycles, int outputChars) {
        List<ChatMessage> h = new ArrayList<>(List.of(ChatMessage.system("只读检查"), ChatMessage.user("看下当前项目；不要修改数据库")));
        for (int i = 0; i < cycles; i++) {
            if (i > 0 && i < userTurns) h.add(ChatMessage.user("继续查看第" + i + "个模块"));
            var call = new ToolCall("read-" + i, "read_file", "{\"path\":\"src/File" + i + ".java\"}");
            h.add(ChatMessage.assistantToolCalls("检查模块" + i, List.of(call)));
            h.add(ChatMessage.toolResult(call, "代码资料".repeat((outputChars + 3) / 4)));
        }
        return List.copyOf(h);
    }
    @Test void summarizesLargeToolHistoryEvenWithOnlyTwoOrThreeUserTurns() {
        for (int turns : List.of(2, 3, 4)) {
            var original = fewTurnInspection(turns, 14, 9000);
            AtomicInteger calls = new AtomicInteger();
            var manager = new ContextManager(counter, new ContextPolicy(true, .85, .65, 4, 1500), (source, max) -> {
                calls.incrementAndGet(); ToolHistory.validate(source, false);
                assertEquals(original.subList(0, source.size()), source);
                return new ContextSummarizer.Result(summary(), TokenUsage.ZERO);
            });
            var projected = manager.project(original, 10000, 12000);
            assertNotNull(projected.snapshot(), "少量用户轮次也应整理已经完成的工具调用组");
            assertFalse(projected.metrics().degraded());
            assertTrue(counter.estimateTokens(projected.messages()) <= 6500);
            assertTrue(projected.messages().containsAll(original.stream().filter(m -> m.role() == ChatMessage.Role.USER).toList()));
            assertTrue(projected.messages().contains(original.getLast()));
            ToolHistory.validate(projected.messages(), false);
            var reused = manager.project(original, 10000, 12000);
            assertEquals(1, calls.get()); assertTrue(reused.metrics().reused());
            assertEquals(projected.messages(), reused.messages());
            assertEquals(counter.estimateTokens(projected.messages()), reused.metrics().beforeTokens(), "复用摘要后应按本次请求而非完整历史计数");
            assertEquals(fewTurnInspection(turns, 14, 9000), original);
        }
    }
    @Test void acceptsUsefulSummaryWhenLatestToolGroupAloneExceedsTargetButFitsBudget() {
        var original = new ArrayList<>(fewTurnInspection(2, 6, 9000));
        var call = new ToolCall("latest", "read_file", "{}");
        original.add(ChatMessage.assistantToolCalls("", List.of(call)));
        original.add(ChatMessage.toolResult(call, "最后一组证据".repeat(4500)));
        AtomicInteger calls = new AtomicInteger();
        var manager = new ContextManager(counter, new ContextPolicy(true, .85, .65, 4, 1500), (source, max) -> {
            calls.incrementAndGet(); return new ContextSummarizer.Result(summary(), TokenUsage.ZERO);
        });
        var projected = manager.project(original, 10000, 40000);
        assertNotNull(projected.snapshot()); assertFalse(projected.metrics().degraded());
        assertTrue(counter.estimateTokens(projected.messages()) > 6500);
        assertTrue(counter.estimateTokens(projected.messages()) <= 10000);
        assertEquals(original.getLast(), projected.messages().getLast());
        manager.project(original, 10000, 40000); assertEquals(1, calls.get());
    }
    @Test void appliesPerToolOutputLimitBeforeDecidingToPayForSummary() {
        var original = fewTurnInspection(2, 10, 40000);
        AtomicInteger calls = new AtomicInteger();
        var manager = new ContextManager(counter, new ContextPolicy(true, .85, .65, 4, 1500), (source, max) -> {
            calls.incrementAndGet(); return new ContextSummarizer.Result(summary(), TokenUsage.ZERO);
        });
        var projected = manager.project(original, 10000, 256);
        assertEquals(0, calls.get(), "已有单条工具上限足以解决预算时不额外请求摘要模型");
        assertFalse(projected.metrics().degraded(), "正常工具预览不应报摘要失败或删除历史块");
        assertNull(projected.snapshot()); assertEquals(original.size(), projected.messages().size());
        ToolHistory.validate(projected.messages(), false);
    }
}
