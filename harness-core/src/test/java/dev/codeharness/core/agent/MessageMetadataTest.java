package dev.codeharness.core.agent;

import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MessageMetadataTest {
    @Test void responseAndMessageCopyMetadataAndKeepOldConstructors() {
        Map<String, String> metadata = new HashMap<>(Map.of("provider.evidence", "opaque"));
        var response = ChatResponse.text("answer", metadata);
        var message = ChatMessage.assistant("answer", metadata);
        metadata.clear();
        assertEquals(Map.of("provider.evidence", "opaque"), response.providerMetadata());
        assertEquals(response.providerMetadata(), message.providerMetadata());
        assertThrows(UnsupportedOperationException.class, () -> message.providerMetadata().clear());
        assertThrows(UnsupportedOperationException.class, () -> response.providerMetadata().clear());
        assertTrue(new ChatResponse("answer", List.of(), null, null).providerMetadata().isEmpty());
        assertTrue(new ChatMessage(ChatMessage.Role.ASSISTANT, "answer", List.of(), null, null, null).providerMetadata().isEmpty());
    }

    @Test void finalResponseMetadataReachesCheckpointAndNextUserTurn() {
        Map<String, String> metadata = Map.of("provider.evidence", "encrypted-state");
        List<List<ChatMessage>> checkpoints = new ArrayList<>();
        var first = new CodeAgent(request -> ChatResponse.text("answer", metadata), new ToolRegistry(List.of()))
                .run("task", AgentRunOptions.defaults(), new AgentListener() {
                    @Override public void onCheckpoint(List<ChatMessage> history) { checkpoints.add(history); }
                });
        assertEquals(AgentResult.Status.COMPLETED, first.status());
        assertEquals(metadata, checkpoints.getLast().getLast().providerMetadata());
        List<ChatMessage> followup = new ArrayList<>(first.transcript());
        followup.add(ChatMessage.user("continue"));
        AtomicInteger requests = new AtomicInteger();
        var second = new CodeAgent(request -> {
            requests.incrementAndGet();
            assertEquals(metadata, request.messages().get(request.messages().size() - 2).providerMetadata());
            return ChatResponse.text("continued");
        }, new ToolRegistry(List.of())).resume(followup, AgentRunOptions.defaults(), AgentListener.NOOP);
        assertEquals(AgentResult.Status.COMPLETED, second.status(), second.error());
        assertEquals(1, requests.get());
    }

    @Test void requiredReplayEvidenceIsCountedAndItsTextCannotBeClipped() {
        var compact = new ContextCompactor();
        var assistant = ChatMessage.assistant("visible".repeat(1000), Map.of("evidence", "x".repeat(2000)));
        var history = List.of(ChatMessage.user("task"), assistant);
        assertTrue(compact.estimateTokens(history) > compact.estimateTokens(List.of(ChatMessage.user("task"), ChatMessage.assistant(assistant.content()))));
        assertThrows(IllegalArgumentException.class, () -> compact.compact(history, 600, 128));
        assertEquals(history, compact.compact(history, 4000, 128));
        AtomicInteger requests = new AtomicInteger();
        var result = new CodeAgent(request -> { requests.incrementAndGet(); return ChatResponse.text("bad"); }, new ToolRegistry(List.of()))
                .resume(history, new AgentRunOptions("", 2, 600, 100, 128, () -> false), AgentListener.NOOP);
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(0, requests.get());
    }

    @Test void legacyCallEvidenceAlsoProtectsVisibleProjection() {
        var call = new ToolCall("id", "read", "{}", Map.of("provider.evidence", "ordered-parts"));
        var history = List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("visible".repeat(1000), List.of(call)),
                ChatMessage.toolResult(call, "result"));
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactor().compact(history, 600, 128));
    }

    @Test void metadataMessageResemblingSummaryIsNotConsumedAndOldBlocksRemainDroppable() {
        var compact = new ContextCompactor();
        String summary = "[harness-context-v1] messages=1;users=0;assistants=1;results=0;calls=0";
        var protectedMessage = ChatMessage.assistant(summary, Map.of("evidence", "raw"));
        var history = List.of(ChatMessage.user("task"), protectedMessage);
        assertEquals(history, compact.compact(history, 1000, 128));
        var old = ChatMessage.assistant("old", Map.of("evidence", "x".repeat(8000)));
        var latest = ChatMessage.assistant("current", Map.of("evidence", "keep"));
        var result = compact.compact(List.of(ChatMessage.user("task"), old, ChatMessage.user("next"), latest), 100, 128);
        assertFalse(result.contains(old));
        assertTrue(result.contains(latest));
    }
}
