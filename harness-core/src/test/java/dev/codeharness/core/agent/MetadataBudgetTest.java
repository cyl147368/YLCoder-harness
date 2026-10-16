package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetadataBudgetTest {
    @Test void opaqueMetadataIsPreservedAndCountedAgainstBudget() {
        ToolCall call = new ToolCall("id", "read", "{}", Map.of("signature", "x".repeat(10_000)));
        var history = List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "output"));
        ContextCompactor compactor = new ContextCompactor();
        assertTrue(compactor.estimateTokens(history) > 2500);
        assertThrows(IllegalArgumentException.class, () -> compactor.compact(history, 500, 128));
        assertEquals(call, compactor.compact(history, 4000, 128).get(1).toolCalls().getFirst());
    }
}
