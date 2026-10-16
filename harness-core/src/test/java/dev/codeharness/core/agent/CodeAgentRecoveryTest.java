package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CodeAgentRecoveryTest {
    @Test void requestObserverCancellationPreventsProviderCall() {
        var cancelled = new AtomicBoolean();
        var requests = new AtomicInteger();
        var result = new CodeAgent(request -> { requests.incrementAndGet(); return ChatResponse.text("unexpected"); },
                ToolRegistry.empty()).run("task", new AgentRunOptions("", 3, 1000, 100, 1000, cancelled::get),
                new AgentListener() {
                    @Override public void onModelRequest(int iteration, ChatRequest request) { cancelled.set(true); }
                });
        assertEquals(AgentResult.Status.CANCELLED, result.status());
        assertEquals(0, requests.get());
    }

    @Test void cancellationDuringModelOrResponseCheckpointCannotReportCompleted() {
        for (boolean duringModel : List.of(true, false)) {
            var cancelled = new AtomicBoolean();
            var displayed = new AtomicInteger();
            var saved = new java.util.ArrayList<List<ChatMessage>>();
            var result = new CodeAgent(request -> {
                if (duringModel) cancelled.set(true);
                return ChatResponse.text("late answer", Map.of("provider.evidence", "opaque"));
            }, ToolRegistry.empty()).run("task", new AgentRunOptions("", 3, 1000, 100, 1000, cancelled::get),
                    new AgentListener() {
                        @Override public void onCheckpoint(List<ChatMessage> history) {
                            saved.add(history);
                            if (history.getLast().role() == ChatMessage.Role.ASSISTANT) cancelled.set(true);
                        }
                        @Override public void onText(String text) { displayed.incrementAndGet(); }
                    });
            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(1, result.iterations());
            assertEquals(0, displayed.get());
            assertEquals("opaque", saved.getLast().getLast().providerMetadata().get("provider.evidence"));
        }
    }

    @Test void duplicateResponseCallIdsFailBeforeMutationOrInvalidCheckpoint() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger checkpoints = new AtomicInteger();
        ToolCall repeated = new ToolCall("same-secret", "edit", "{}");
        ChatModel model = request -> new ChatResponse("editing", List.of(repeated, repeated), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        AgentListener listener = new AgentListener() {
            @Override public void onCheckpoint(List<ChatMessage> transcript) {
                checkpoints.incrementAndGet();
                assertTrue(transcript.stream().noneMatch(m -> !m.toolCalls().isEmpty()));
            }
        };
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(tool("edit", executions))))
                .run("fix", AgentRunOptions.defaults(), listener);
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertTrue(result.error().contains("重复的工具调用 ID"));
        assertFalse(result.error().contains("same-secret"));
        assertEquals(0, executions.get());
        assertEquals(1, checkpoints.get());
        assertEquals(1, result.iterations());
    }

    @Test void malformedExistingEvidenceFailsBeforeCheckpointModelOrToolSideEffects() {
        ToolCall call = new ToolCall("call", "edit", "{}");
        ToolCall other = new ToolCall("unmatched", "edit", "{}");
        ChatMessage assistant = ChatMessage.assistantToolCalls("", List.of(call));
        List<List<ChatMessage>> invalid = List.of(
                List.of(ChatMessage.user("task"), ChatMessage.toolResult(call, "orphan")),
                List.of(ChatMessage.user("task"), assistant, ChatMessage.toolResult(call, "first"), ChatMessage.toolResult(call, "second")),
                List.of(ChatMessage.user("task"), assistant, ChatMessage.toolResult(other, "unmatched")),
                List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call, call))),
                List.of(ChatMessage.user("task"), assistant, new ChatMessage(ChatMessage.Role.TOOL, "wrong", List.of(), "call", "other")));
        for (List<ChatMessage> history : invalid) {
            AtomicInteger effects = new AtomicInteger();
            ChatModel model = request -> { effects.incrementAndGet(); return ChatResponse.text("unexpected"); };
            AgentListener listener = new AgentListener() {
                @Override public void onCheckpoint(List<ChatMessage> transcript) { effects.incrementAndGet(); }
            };
            AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(tool("edit", effects))))
                    .resume(history, AgentRunOptions.defaults(), listener);
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(0, effects.get());
            assertEquals(history, result.transcript(), "不要悄悄丢弃矛盾证据");
        }
    }
    @Test
    void repairsInterruptedToolResultsWithoutReplayingSideEffects() {
        ToolCall completed = new ToolCall("complete", "edit", "{}");
        ToolCall interrupted = new ToolCall("pending", "edit", "{}");
        AtomicInteger executions = new AtomicInteger();
        Tool edit = tool("edit", executions);
        List<ChatMessage> history = List.of(ChatMessage.system("instructions"), ChatMessage.user("fix"),
                ChatMessage.assistantToolCalls("Editing", List.of(completed, interrupted)),
                ChatMessage.toolResult(completed, "saved"));
        ChatModel model = request -> {
            List<ChatMessage> results = request.messages().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).toList();
            assertEquals(2, results.size());
            assertEquals("complete", results.get(0).toolCallId());
            assertEquals("saved", results.get(0).content());
            assertEquals("pending", results.get(1).toolCallId());
            assertTrue(results.get(1).content().contains("实际影响未知"));
            return ChatResponse.text("Verified current state.");
        };
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(edit))).resume(history, AgentRunOptions.defaults(), null);
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(0, executions.get(), "恢复时不得重放实际影响未知的操作");
        assertEquals(4, history.size(), "恢复不得修改持久化输入");
    }

    @Test
    void failedDurableCheckpointPreventsRequestedToolSideEffects() {
        AtomicInteger executions = new AtomicInteger();
        ChatModel model = request -> new ChatResponse("editing", List.of(new ToolCall("c", "edit", "{}")),
                TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        AgentListener persistence = new AgentListener() {
            @Override public void onCheckpoint(List<ChatMessage> transcript) {
                if (transcript.getLast().role() == ChatMessage.Role.ASSISTANT) throw new IllegalStateException("disk full");
            }
        };
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(tool("edit", executions))))
                .run("fix", AgentRunOptions.defaults(), persistence);
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertTrue(result.error().contains("disk full"));
        assertEquals(0, executions.get());
    }

    @Test
    void cancellationBeforeModelCallHasNoProviderOrToolSideEffects() {
        AtomicInteger requests = new AtomicInteger();
        AgentRunOptions options = new AgentRunOptions(null, 3, 1000, 100, 1000, () -> true);
        AgentResult result = new CodeAgent(request -> { requests.incrementAndGet(); return ChatResponse.text("unexpected"); },
                ToolRegistry.empty()).run("fix", options);
        assertEquals(AgentResult.Status.CANCELLED, result.status());
        assertEquals(0, requests.get());
        assertEquals(0, result.iterations());
    }

    @Test
    void cancellationBetweenToolsStopsRemainingSideEffects() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger executions = new AtomicInteger();
        Tool edit = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("edit", "Edit", Map.of("type", "object")); }
            @Override public String execute(String args) { executions.incrementAndGet(); cancelled.set(true); return "saved"; }
        };
        ChatModel model = request -> new ChatResponse("", List.of(new ToolCall("first", "edit", "{}"), new ToolCall("second", "edit", "{}")),
                TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(edit))).run("fix",
                new AgentRunOptions(null, 3, 1000, 100, 1000, cancelled::get));
        assertEquals(AgentResult.Status.CANCELLED, result.status());
        assertEquals(1, executions.get());
        assertEquals(1, result.transcript().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).count());
    }

    @Test
    void truncatedAndFilteredResponsesAreNotSuccessfulTasks() {
        for (FinishReason reason : List.of(FinishReason.LENGTH, FinishReason.CONTENT_FILTER)) {
            AgentResult result = new CodeAgent(request -> new ChatResponse("partial", List.of(), TokenUsage.ZERO, reason),
                    ToolRegistry.empty()).run("fix", AgentRunOptions.defaults());
            assertEquals(AgentResult.Status.FAILED, result.status(), reason.toString());
            assertFalse(result.error().isBlank());
            assertEquals("partial", result.finalAnswer());
        }
    }

    @Test
    void providerFailurePreservesTaskForRecovery() {
        AgentResult result = new CodeAgent(request -> { throw new IllegalStateException("provider unavailable"); }, ToolRegistry.empty())
                .run("fix", AgentRunOptions.defaults());
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals("provider unavailable", result.error());
        assertEquals("fix", result.transcript().getLast().content());
    }

    private static Tool tool(String name, AtomicInteger executions) {
        return new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition(name, "Edit", Map.of("type", "object")); }
            @Override public String execute(String args) { executions.incrementAndGet(); return "saved"; }
        };
    }
}
