package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextManagerClearingTest {
    private final ContextCompactor counter = new ContextCompactor();

    private ContextManager manager() {
        return new ContextManager(counter, new ContextPolicy(true, .85, .65, 2, 1500), null);
    }
    private static List<ChatMessage> reads(int count, int chars) {
        List<ChatMessage> h = new ArrayList<>(List.of(ChatMessage.system("rules"), ChatMessage.user("inspect")));
        for (int i = 0; i < count; i++) {
            var call = new ToolCall("read-" + i, "read_file", "{}");
            h.add(ChatMessage.assistantToolCalls("", List.of(call)));
            h.add(ChatMessage.toolResult(call, (i == 0 ? "short" : "x".repeat(chars))));
        }
        h.add(ChatMessage.user("continue"));
        return List.copyOf(h);
    }

    @Test void advanceClearedKeepsRecentResultsAndSkipsShortOnes() {
        var h = reads(12, 4000);
        var m = manager();
        m.advanceCleared(h);
        // 12 candidates, 8 most recent kept, first one is too short to clear.
        assertEquals(Set.of("read-1", "read-2", "read-3"), m.clearedIds());
    }

    @Test void microCompactIsIdentityWithoutClearedIds() {
        var h = reads(12, 4000);
        assertSame(h, ContextManager.microCompact(h, Set.of()));
    }

    @Test void microCompactReplacesOnlyClearedResultsAndIsStable() {
        var h = reads(12, 4000);
        var once = ContextManager.microCompact(h, Set.of("read-2"));
        var twice = ContextManager.microCompact(once, Set.of("read-2"));
        assertEquals(once, twice);
        long placeholders = once.stream().filter(x -> x.content().startsWith(ContextManager.CLEARED_PREFIX)).count();
        assertEquals(1, placeholders);
        ToolHistory.validate(once, false);
    }

    @Test void projectDoesNotClearBelowWarningLine() {
        var m = manager();
        m.project(reads(12, 4000), 1_000_000, 100_000);
        assertTrue(m.clearedIds().isEmpty());
    }

    @Test void clearedResultsStayClearedAndRestoreResets() {
        var m = manager();
        var h = reads(12, 4000);
        m.project(h, 10_000, 100_000);
        var first = m.clearedIds();
        assertFalse(first.isEmpty());
        // Even with a huge budget later, already-cleared results keep their placeholder.
        var later = m.project(h, 1_000_000, 100_000);
        assertEquals(first, m.clearedIds());
        assertTrue(later.messages().stream().anyMatch(x -> x.content().startsWith(ContextManager.CLEARED_PREFIX)));
        m.restore(null);
        assertTrue(m.clearedIds().isEmpty());
    }
}
