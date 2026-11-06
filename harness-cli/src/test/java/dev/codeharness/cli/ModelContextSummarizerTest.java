package dev.codeharness.cli;

import dev.codeharness.core.agent.*;
import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelContextSummarizerTest {
    static String valid() { return "{\"goals\":[\"实现功能\"],\"constraints\":[\"不得删除数据\"],\"decisions\":[],\"completed\":[],\"pending\":[\"测试\"],\"failures\":[],\"references\":[\"src/A.java\"]}"; }
    @Test void usesNoToolsAndRedactsSourceAndResult() {
        String secret = "unit-test-credential-123456";
        var summarizer = new ModelContextSummarizer(request -> {
            assertTrue(request.tools().isEmpty()); assertEquals(2, request.messages().size());
            assertFalse(request.messages().get(1).content().contains(secret));
            assertTrue(request.messages().get(1).content().contains("不得删除数据"));
            return new ChatResponse(valid().replace("src/A.java", secret), List.of(), new TokenUsage(17, 11, 0), FinishReason.STOP);
        }, InputTokenCounter.UTF16, 4000, List.of(secret));
        var result = summarizer.summarize(List.of(ChatMessage.system("不应传输系统全文"), ChatMessage.user("不得删除数据 " + secret)), 1000);
        assertFalse(result.summary().render().contains(secret)); assertEquals(new TokenUsage(17, 11, 0), result.usage());
    }
    @Test void rejectsMalformedTruncatedOrToolBearingRepliesAndRecordsConsumedUsage() {
        for (ChatResponse reply : List.of(ChatResponse.text("不是JSON"), ChatResponse.text(valid().replace("\"goals\"", "\"bad\"")),
                new ChatResponse(valid(), List.of(new ToolCall("1", "shell", "{}")), new TokenUsage(2, 3, 0), FinishReason.TOOL_CALLS),
                new ChatResponse(valid(), List.of(), TokenUsage.ZERO, FinishReason.LENGTH))) {
            var summarizer = new ModelContextSummarizer(r -> reply, InputTokenCounter.UTF16, 4000, List.of());
            var error = assertThrows(ContextSummarizer.Failure.class, () -> summarizer.summarize(List.of(ChatMessage.user("任务")), 1000));
            assertEquals(reply.usage(), error.usage());
        }
    }
    @Test void splitsOriginalIntoBoundedChunksRatherThanSummarizingOldSummary() {
        AtomicInteger calls = new AtomicInteger();
        var summarizer = new ModelContextSummarizer(request -> {
            calls.incrementAndGet(); assertFalse(request.messages().get(1).content().contains("ylc-context-v2"));
            assertTrue(new ContextCompactor().estimateTokens(request.messages()) <= 2000);
            return ChatResponse.text(valid());
        }, InputTokenCounter.UTF16, 2000, List.of());
        var result = summarizer.summarize(List.of(ChatMessage.user("开始"), ChatMessage.assistant("原文".repeat(2200)),
                ChatMessage.user("继续"), ChatMessage.assistant("原文".repeat(2200))), 1000);
        assertTrue(calls.get() >= 2); assertEquals(1, result.summary().goals().size());
    }
    @Test void oversizedAtomicSourceFailsWithoutPartialCalls() {
        AtomicInteger calls = new AtomicInteger();
        var summarizer = new ModelContextSummarizer(request -> { calls.incrementAndGet(); return ChatResponse.text(valid()); }, InputTokenCounter.UTF16, 1000, List.of());
        assertThrows(ContextSummarizer.Failure.class, () -> summarizer.summarize(List.of(ChatMessage.user("原文".repeat(4000))), 1000));
        assertEquals(0, calls.get());
    }
}
