package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompletionTest {
    @Test void commandAndArgumentCandidatesNeverInsertWholeCommandsIntoArgumentPosition() {
        var choices = CommandCompletion.candidates(List.of(new SessionStore.Summary("saved-session", "p", "m", "COMPLETED", "", "")));
        assertEquals(List.of("/resume"), CommandCompletion.words("/res", "/res", choices));
        assertEquals(List.of("/status", "/stream"), CommandCompletion.words("/st", "/st", choices));
        assertEquals(List.of("off"), CommandCompletion.words("/stream of", "of", choices));
        assertEquals(List.of("on", "off"), CommandCompletion.words("/stream ", "", choices));
        assertEquals(List.of("1", "saved-session"), CommandCompletion.words("/resume ", "", choices));
        assertTrue(CommandCompletion.words("/missing ", "", choices).isEmpty());
        assertTrue(CommandCompletion.words("普通任务", "普通任务", choices).isEmpty());
        assertEquals(List.of("all"), CommandCompletion.words("/history a", "a", choices));
    }
}
