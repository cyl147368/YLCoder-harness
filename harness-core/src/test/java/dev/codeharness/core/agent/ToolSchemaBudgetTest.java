package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaBudgetTest {
    @Test void schemasAreDeeplyCopiedAndDeterministicallyOrdered() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("z", Map.of("type", "string"));
        properties.put("a", Map.of("type", "integer"));
        ToolDefinition definition = new ToolDefinition("edit", "Edit", Map.of("properties", properties, "type", "object"));
        properties.put("injected", Map.of());
        Map<?, ?> copied = (Map<?, ?>) definition.inputSchema().get("properties");
        assertEquals(List.of("a", "z"), new ArrayList<>(copied.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> definition.inputSchema().put("x", 1));
    }

    @Test void giantSchemaPreventsRequestBeforeProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        Tool tool = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("big", "x".repeat(10_000), Map.of("type", "object")); }
            public String execute(String args) { fail("不得执行工具"); return ""; }
        };
        AgentResult result = new CodeAgent(request -> { calls.incrementAndGet(); return ChatResponse.text("done"); },
                new ToolRegistry(List.of(tool))).run("task", new AgentRunOptions("system", 3, 128, 100, 128, () -> false));
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertTrue(result.error().contains("工具结构"));
        assertEquals(0, calls.get());
    }
}
