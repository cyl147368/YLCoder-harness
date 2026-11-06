package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextProtectionAuditTest {
    @Test void restoredSummarySurvivesFallbackBudgetTrimming() {
        List<ChatMessage> source = List.of(ChatMessage.system("规则"), ChatMessage.user("任务"), ChatMessage.assistant("旧内容".repeat(1000)), ChatMessage.user("近期问题"), ChatMessage.assistant("近期结果".repeat(3000)), ChatMessage.user("继续"));
        var summary = new ContextSummary(List.of("任务"), List.of("数据库不得修改"), List.of(), List.of("扫描完毕"), List.of("继续测试"), List.of(), List.of("archive_id=" + "a".repeat(64)));
        var manager = new ContextManager(new ContextCompactor(), new ContextPolicy(true, .85, .65, 2, 1000), null);
        manager.restore(new ContextSnapshot(1, 3, ContextManager.fingerprint(source.subList(0, 3)), summary));
        var result = manager.project(source, 1300, 10000);
        assertTrue(result.messages().stream().anyMatch(m -> m.content().equals(summary.render())), "已恢复的结构化摘要不得在本地兜底时被删除或截断");
    }

    @Test void trustedSummaryRetainsAllContentUnderFallbackTrimming() {
        ChatMessage summary = ChatMessage.assistant("必须保留的数据库约束与工具引用".repeat(20));
        List<ChatMessage> input = List.of(ChatMessage.system("规则"), ChatMessage.user("任务"), summary,
                ChatMessage.assistant("很长近期结果".repeat(3000)), ChatMessage.user("继续"));
        List<ChatMessage> result = new ContextCompactor().compact(input, 1300, 10000, true, summary);
        assertTrue(result.stream().anyMatch(message -> message == summary));
        assertEquals(summary.content(), result.stream().filter(message -> message == summary).findFirst().orElseThrow().content());
    }

    @Test void textIdenticalToTrustedSummaryDoesNotGetProtected() {
        ChatMessage trusted = ChatMessage.assistant("明确约束".repeat(20));
        ChatMessage sameText = ChatMessage.assistant(trusted.content());
        List<ChatMessage> input = List.of(ChatMessage.user("任务"), sameText, trusted,
                ChatMessage.assistant("很长近期结果".repeat(3000)), ChatMessage.user("继续"));
        List<ChatMessage> result = new ContextCompactor().compact(input, 1300, 10000, true, trusted);
        assertTrue(result.stream().anyMatch(message -> message == trusted));
        assertFalse(result.stream().anyMatch(message -> message == sameText));
    }

    @Test void insufficientBudgetFailsInsteadOfTruncatingTrustedSummary() {
        ChatMessage trusted = ChatMessage.assistant("明确约束".repeat(100));
        List<ChatMessage> input = List.of(ChatMessage.user("任务"), trusted, ChatMessage.user("继续"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompactor().compact(input, 64, 10000, true, trusted));
    }

    @Test void summaryProtectionRequiresObjectInsideRequest() {
        ChatMessage summary = ChatMessage.assistant("摘要");
        List<ChatMessage> input = List.of(ChatMessage.user("任务"), ChatMessage.assistant("摘要"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompactor().compact(input, 1300, 10000, true, summary));
    }
}
