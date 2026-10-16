package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoreBoundaryTest {
    @Test
    void oversizedSystemAndTaskFailBeforeProviderCall() {
        for (boolean longSystem : List.of(true, false)) {
            AtomicInteger requests = new AtomicInteger();
            AgentRunOptions options = new AgentRunOptions(longSystem ? "s".repeat(8000) : "system", 3, 64, 100, 128, () -> false);
            AgentResult result = new CodeAgent(request -> { requests.incrementAndGet(); return ChatResponse.text("unexpected"); }, ToolRegistry.empty())
                    .run(longSystem ? "task" : "task ".repeat(1600), options);
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertTrue(result.error().contains("超过上下文预算"));
            assertEquals(0, requests.get());
            assertEquals(longSystem ? "task" : "task ".repeat(1600), result.transcript().getLast().content());
        }
    }

    @Test
    void oversizedToolArgumentsAreRejectedWithoutCorruptingCallJson() {
        ContextCompactor compactor = new ContextCompactor();
        ToolCall call = new ToolCall("c", "read", "{\"path\":\"" + "x".repeat(8000) + "\"}");
        List<ChatMessage> input = List.of(ChatMessage.system("system"), ChatMessage.user("task"),
                ChatMessage.assistantToolCalls("reading", List.of(call)), ChatMessage.toolResult(call, "result"));
        assertThrows(IllegalArgumentException.class, () -> compactor.compact(input, 64, 128));
        assertEquals(call, input.get(2).toolCalls().getFirst());
    }

    @Test
    void compactionPreservesAllOriginalSystemInstructionsAndLatestTask() {
        ContextCompactor compactor = new ContextCompactor();
        List<ChatMessage> input = List.of(ChatMessage.system("primary"), ChatMessage.system("second instruction"),
                ChatMessage.user("Do not remove this precise request"), ChatMessage.assistant("text ".repeat(500)));
        List<ChatMessage> output = compactor.compact(input, 64, 128);
        assertTrue(compactor.estimateTokens(output) <= 64);
        assertEquals(input.subList(0, 3), output.subList(0, 3));
    }

    @Test
    void toolDefinitionsPreserveRegistrationOrder() {
        List<String> names = List.of("zebra", "alpha", "write", "read", "test", "search", "compile", "diff");
        ToolRegistry registry = new ToolRegistry(names.stream().map(name -> tool(name, new AtomicInteger())).toList());
        assertEquals(names, registry.definitions().stream().map(ToolDefinition::name).toList());
        assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
    }

    @Test
    void incompleteProviderResponsesNeverExecuteTools() {
        for (FinishReason reason : List.of(FinishReason.LENGTH, FinishReason.CONTENT_FILTER, FinishReason.OTHER)) {
            AtomicInteger executions = new AtomicInteger();
            ChatModel model = request -> new ChatResponse("partial", List.of(new ToolCall("c", "edit", "{}")), TokenUsage.ZERO, reason);
            AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(tool("edit", executions))))
                    .run("fix", AgentRunOptions.defaults());
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(0, executions.get(), reason.toString());
        }
    }

    @Test
    void unknownAndInconsistentFinishReasonsDoNotComplete() {
        for (FinishReason reason : List.of(FinishReason.OTHER, FinishReason.TOOL_CALLS)) {
            AgentResult result = new CodeAgent(request -> new ChatResponse("partial", List.of(), TokenUsage.ZERO, reason), ToolRegistry.empty())
                    .run("fix", AgentRunOptions.defaults());
            assertEquals(AgentResult.Status.FAILED, result.status());
        }
    }

    @Test
    void interruptedToolCancelsRunAndPreservesInterruptFlag() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        Tool interrupted = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("edit", "Edit", Map.of("type", "object")); }
            @Override public String execute(String args) throws InterruptedException { executions.incrementAndGet(); throw new InterruptedException("cancelled"); }
        };
        ChatModel model = request -> { requests.incrementAndGet(); return new ChatResponse("", List.of(new ToolCall("first", "edit", "{}"),
                new ToolCall("second", "edit", "{}")), TokenUsage.ZERO, FinishReason.TOOL_CALLS); };
        try {
            AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(interrupted))).run("fix", AgentRunOptions.defaults());
            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, requests.get());
            assertEquals(1, executions.get());
            assertTrue(result.transcript().getLast().content().contains("实际影响未知"));
        } finally {
            Thread.interrupted();
        }
    }

    private static Tool tool(String name, AtomicInteger executions) {
        return new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition(name, "Tool", Map.of("type", "object")); }
            @Override public String execute(String args) { executions.incrementAndGet(); return "saved"; }
        };
    }
}
