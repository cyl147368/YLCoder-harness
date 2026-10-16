package dev.codeharness.cli;

import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionMetadataTest {
    @TempDir Path workspace;

    @Test void opaqueProviderMetadataSurvivesSaveAndLoad() throws Exception {
        SessionStore store = new SessionStore(workspace);
        ToolCall call = new ToolCall("call-1", "read_file", "{}",
                Map.of("gemini.thoughtSignature", "opaque+/=signature", "gemini.functionCallId", "wire-1"));
        var transcript = List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "文件内容"));
        String id = store.newId();
        store.save(id, "gemini", "example-model", "RUNNING", transcript);
        assertEquals(call, store.load(id).transcript().get(1).toolCalls().getFirst());
    }

    @Test void legacySessionWithoutMetadataStillLoads() throws Exception {
        SessionStore store = new SessionStore(workspace);
        String id = store.newId();
        ToolCall call = new ToolCall("call-1", "read_file", "{}");
        store.save(id, "custom", "model", "RUNNING",
                List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call))));
        Path file = workspace.resolve(".harness/sessions/" + id + ".json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var saved = json.readTree(file.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.path("transcript").get(1).path("toolCalls").get(0)).remove("providerMetadata");
        json.writeValue(file.toFile(), saved);
        assertEquals(Map.of(), store.load(id).transcript().get(1).toolCalls().getFirst().providerMetadata());
    }
}
