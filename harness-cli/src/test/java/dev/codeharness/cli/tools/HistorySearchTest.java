package dev.codeharness.cli.tools;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistorySearchTest {
    @Test void readsFullOriginalHistoryIncludingToolArgumentsWithoutExposingSystemOrSecrets() throws Exception {
        String key = "fixture-private-credential-8278";
        var history = List.of(ChatMessage.system("needle 私有系统规则"),
                ChatMessage.user("needle 用户原始要求 " + key),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("id", "read_file", "{\"path\":\"needle.java\"}"))),
                ChatMessage.toolResult(new ToolCall("id", "read_file", "{}"), "needle 工具结果"));
        var tool = new HistorySearch(List.of(key)); tool.update(history);
        String result = tool.execute("{\"query\":\"needle\"}");
        assertTrue(result.contains("用户原始要求"), result);
        assertTrue(result.contains("needle.java"), result);
        assertTrue(result.contains("工具结果"), result);
        assertFalse(result.contains(key)); assertFalse(result.contains("私有系统规则"));
        assertEquals(key, history.get(1).content().substring(history.get(1).content().indexOf(key)));
    }
    @Test void paginationIsBoundedAndUpdateReplacesCurrentHistory() throws Exception {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 20; i++) history.add(ChatMessage.user("match " + i + " ".repeat(700)));
        var tool = new HistorySearch(List.of()); tool.update(history);
        String first = tool.execute("{\"query\":\"match\"}");
        assertTrue(first.contains("start_message=9"), first);
        assertFalse(first.contains("match 8")); assertTrue(first.length() < 7000);
        String next = tool.execute("{\"query\":\"match\",\"start_message\":9}");
        assertTrue(next.contains("match 8")); assertFalse(next.contains("消息 1 ·"));
        tool.update(List.of(ChatMessage.user("新任务")));
        assertTrue(tool.execute("{\"query\":\"match\"}").contains("未找到"));
    }
    @Test void rejectsInvalidOrAmbiguousInputAndSupportsCancellation() throws Exception {
        var tool = new HistorySearch(List.of());
        for (String args : List.of("{}", "{\"query\":\"\"}", "{\"query\":\"ok\",\"start_message\":0}",
                "{\"query\":\"ok\",\"start_message\":2.5}", "{\"query\":\"ok\",\"extra\":1}",
                "{\"query\":\"one\",\"query\":\"two\"}", "{\"query\":\"ok\"} {}")) {
            assertThrows(Exception.class, () -> tool.execute(args), args);
        }
        tool.update(List.of(ChatMessage.user("match")));
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> tool.execute("{\"query\":\"match\"}")); }
        finally { Thread.interrupted(); }
    }
}
