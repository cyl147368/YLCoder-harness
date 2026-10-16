package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeAgentTest {
    @Test void synchronousModelDoesNotInventStreamingDeltas() {
        AtomicInteger deltas = new AtomicInteger();
        ChatModel model = request -> ChatResponse.text("Done");
        var result = new CodeAgent(model, ToolRegistry.empty()).run("Do task", AgentRunOptions.defaults(), new AgentListener() {
            @Override public void onTextDelta(String delta) { deltas.incrementAndGet(); }
        });
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(0, deltas.get());
    }

    @Test void forwardsStreamingDeltasAndIsolatesObserverFailures() {
        AtomicInteger deltas = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse complete(dev.codeharness.core.model.ChatRequest request) {
                fail("智能体应使用支持流式输出的重载");
                return null;
            }
            @Override public ChatResponse complete(dev.codeharness.core.model.ChatRequest request, java.util.function.Consumer<String> listener) {
                listener.accept("Do");
                listener.accept("ne");
                return ChatResponse.text("Done");
            }
        };
        var result = new CodeAgent(model, ToolRegistry.empty()).run("Do task", AgentRunOptions.defaults(), new AgentListener() {
            @Override public void onTextDelta(String delta) { deltas.incrementAndGet(); throw new IllegalStateException("Display failed"); }
        });
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals("Done", result.finalAnswer());
        assertEquals(2, deltas.get());
    }

    @Test
    void executesToolThenReturnsFinalAnswerAndAccumulatesUsage() {
        AtomicInteger turns = new AtomicInteger();
        ChatModel model = request -> {
            assertEquals("Fix the bug", request.messages().stream().filter(m -> m.role() == ChatMessage.Role.USER).findFirst().orElseThrow().content());
            if (turns.getAndIncrement() == 0) {
                return new ChatResponse("I will inspect the project.",
                        List.of(new ToolCall("call-1", "inspect", "{}")),
                        new TokenUsage(10, 3, 4), FinishReason.TOOL_CALLS);
            }
            assertEquals("project looks healthy", request.messages().getLast().content());
            return new ChatResponse("Done.", List.of(), new TokenUsage(7, 2, 1), FinishReason.STOP);
        };
        Tool inspect = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("inspect", "Inspect workspace", Map.of("type", "object")); }
            @Override public String execute(String argumentsJson) { assertEquals("{}", argumentsJson); return "project looks healthy"; }
        };
        CodeAgent agent = new CodeAgent(model, new ToolRegistry(List.of(inspect)));
        AgentResult result = agent.run("Fix the bug", AgentRunOptions.defaults());
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals("Done.", result.finalAnswer());
        assertEquals(new TokenUsage(17, 5, 5), result.usage());
        assertEquals(2, result.iterations());
        assertTrue(result.elapsed().compareTo(Duration.ZERO) >= 0);
        assertEquals(1, result.transcript().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).count());
    }

    @Test
    void failsClosedForUnknownToolAndContinuesConversation() {
        AtomicInteger turns = new AtomicInteger();
        ChatModel model = request -> turns.getAndIncrement() == 0
                ? new ChatResponse("", List.of(new ToolCall("missing-call", "missing", "{}")), TokenUsage.ZERO, FinishReason.TOOL_CALLS)
                : ChatResponse.text("Recovered");
        AgentResult result = new CodeAgent(model, ToolRegistry.empty()).run("Do task", AgentRunOptions.defaults());
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertTrue(result.transcript().stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL && m.content().contains("不可用")));
    }

    @Test
    void stopsAtIterationLimitWhenModelKeepsRequestingTools() {
        ChatModel model = request -> new ChatResponse("", List.of(new ToolCall("call", "noop", "{}")), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        Tool noop = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("noop", "No operation", Map.of("type", "object")); }
            @Override public String execute(String args) { return "ok"; }
        };
        AgentRunOptions options = new AgentRunOptions(null, 1, 1000, 100, 1000, () -> false);
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(noop))).run("Do task", options);
        assertEquals(AgentResult.Status.MAX_ITERATIONS, result.status());
        assertEquals(1, result.iterations());
        assertEquals(1, result.transcript().stream().filter(m -> m.role() == ChatMessage.Role.ASSISTANT && !m.toolCalls().isEmpty()).count());
    }
}
