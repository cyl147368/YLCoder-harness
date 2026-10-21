package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationRewindTest {
    @Test void rejectsPartialToolBoundaryAndKeepsOriginalImmutable() {
        var call = new ToolCall("pending", "write_file", "{}");
        var history = List.of(ChatMessage.system("系统"), ChatMessage.user("第一项"),
                ChatMessage.assistantToolCalls("", List.of(call)), ChatMessage.user("无效的第二项"));
        assertEquals(List.of(ChatMessage.system("系统")), ConversationRewind.before(history, 1));
        assertThrows(IllegalArgumentException.class, () -> ConversationRewind.before(history, 2));
        assertEquals(4, history.size());
    }

    @Test void pickerHasStableNumbersAndSanitizesTerminalControls() {
        var history = new ArrayList<ChatMessage>();
        for (int index = 1; index <= 24; index++) history.add(ChatMessage.user("任务" + index + "\033[2J\n新行"));
        var lines = new ArrayList<String>();
        ConversationRewind.render(ConversationRewind.points(history), false, lines::add);
        String screen = String.join("\n", lines);
        assertFalse(screen.contains("\033"));
        assertFalse(screen.contains("  4. "));
        assertTrue(screen.contains("  5. "));
        assertTrue(screen.contains("  24. "));
        assertEquals(3, ConversationRewind.before(history, 4).size());
    }
}
