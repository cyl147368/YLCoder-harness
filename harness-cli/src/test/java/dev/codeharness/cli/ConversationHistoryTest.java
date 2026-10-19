package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {
    @Test void showsConversationInOrderWithoutSystemPromptToolPayloadOrTerminalControls() {
        var messages = List.of(ChatMessage.system("private-system"), ChatMessage.user("问题\n下一行 😀"),
                ChatMessage.assistant("答复\033[2J\r\u202e"),
                ChatMessage.assistantToolCalls("正在检查", List.of(new ToolCall("call", "read_file", "{\"path\":\"private-args\"}"))),
                ChatMessage.toolResult(new ToolCall("call", "read_file", "{}"), "private-tool-output"));
        String text = render(messages, null);
        assertTrue(text.indexOf("问题") < text.indexOf("答复"));
        assertTrue(text.contains("    下一行 😀"));
        assertTrue(text.contains("工具调用：read_file"));
        assertTrue(text.contains("工具结果"));
        assertFalse(text.contains("private-"));
        assertFalse(text.contains("\033"));
        assertFalse(text.contains("\r"));
        assertFalse(text.contains("\u202e"));
    }

    @Test void defaultPageIsLatestAndExplicitPagesCoverEarlierHistory() {
        var messages = new ArrayList<ChatMessage>();
        for (int index = 0; index < 25; index++) messages.add(ChatMessage.user("消息-" + index + "-结束"));
        String latest = render(messages, null);
        assertTrue(latest.contains("第 3/3 页"));
        assertTrue(latest.contains("消息-24-结束"));
        assertFalse(latest.contains("消息-0-结束"));
        String first = render(messages, "1");
        assertTrue(first.contains("消息-0-结束"));
        assertTrue(first.contains("消息-11-结束"));
        assertFalse(first.contains("消息-12-结束"));
        String all = render(messages, "all");
        for (int index = 0; index < 25; index++) assertTrue(all.contains("消息-" + index + "-结束"));
        for (String invalid : List.of("0", "-1", "4", "bad", "999999999999999999"))
            assertTrue(render(messages, invalid).contains("页码无效"));
        assertTrue(render(List.of(ChatMessage.system("system")), null).contains("尚无对话记录"));
    }

    @Test void longMessagesAndManyToolRecordsAreBoundedWithoutChangingTranscript() {
        var messages = List.of(ChatMessage.user("开头" + "x".repeat(100_000) + "末尾"));
        String rendered = render(messages, "all");
        assertTrue(rendered.contains("显示已截断"));
        assertFalse(rendered.contains("末尾"));
        assertTrue(messages.getFirst().content().endsWith("末尾"));
        var tools = new ArrayList<ChatMessage>();
        for (int index = 0; index < 2000; index++) tools.add(ChatMessage.toolResult(new ToolCall("call" + index, "tool", "{}"), "private"));
        rendered = render(tools, "all");
        assertTrue(rendered.contains("本次显示已达上限"));
        assertTrue(rendered.length() < 120_000);
    }

    private String render(List<ChatMessage> messages, String selection) {
        var lines = new ArrayList<String>();
        ConversationHistory.render(messages, selection, lines::add);
        return String.join("\n", lines);
    }
}
