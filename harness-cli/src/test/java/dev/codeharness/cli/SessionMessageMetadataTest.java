package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codeharness.core.agent.AgentListener;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionMessageMetadataTest {
    @TempDir Path workspace;
    private static final String ID = "message-metadata";
    private static final String ANSWER = "第一次最终答案";
    private static final Map<String, String> METADATA = Map.of(
            "openai.responses.outputItems", "[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque+/==\"}]",
            "fixture.opaque", "value\\with\tescapes\nand-unicode-\u4e2d\ud83d\ude00");

    @Test void oldMissingAndNullMessageMetadataLoadAsEmpty() throws Exception {
        SessionStore store = new SessionStore(workspace);
        store.save(ID, "custom", "fixture", "COMPLETED", List.of(ChatMessage.user("task"), ChatMessage.assistant(ANSWER, METADATA)));
        Path checkpoint = workspace.resolve(".harness/sessions/" + ID + ".json");
        ObjectMapper json = new ObjectMapper();
        byte[] original = Files.readAllBytes(checkpoint);
        for (boolean nullField : List.of(false, true)) {
            var root = json.readTree(original);
            for (var message : root.path("transcript")) {
                ObjectNode node = (ObjectNode) message;
                if (nullField) node.putNull("providerMetadata");
                else node.remove("providerMetadata");
            }
            Files.write(checkpoint, json.writeValueAsBytes(root));
            SessionStore.Session loaded = store.load(ID);
            assertTrue(loaded.transcript().stream().allMatch(m -> m.providerMetadata().isEmpty()));
            assertEquals(ANSWER, loaded.transcript().getLast().content());
        }
        assertTrue(new ChatMessage(ChatMessage.Role.ASSISTANT, ANSWER, List.of(), null, null).providerMetadata().isEmpty());
    }

    @Test void immutableMessageMetadataRoundTripsExactValues() throws Exception {
        Map<String, String> mutable = new HashMap<>(METADATA);
        ChatMessage assistant = ChatMessage.assistant(ANSWER, mutable);
        mutable.clear();
        assertEquals(METADATA, assistant.providerMetadata());
        assertThrows(UnsupportedOperationException.class, () -> assistant.providerMetadata().put("extra", "tampering"));
        var response = ChatResponse.text(ANSWER, new HashMap<>(METADATA));
        assertEquals(METADATA, response.providerMetadata());
        assertThrows(UnsupportedOperationException.class, () -> response.providerMetadata().clear());
        SessionStore store = new SessionStore(workspace);
        store.save(ID, "custom", "fixture", "COMPLETED", List.of(ChatMessage.user("task"), assistant));
        ChatMessage loaded = store.load(ID).transcript().getLast();
        assertEquals(assistant, loaded);
        assertExactMetadata(loaded);
        assertThrows(UnsupportedOperationException.class, () -> loaded.providerMetadata().clear());
    }

    @Test void finalAnswerMetadataSurvivesSeparateProcessCheckpointAndUserFollowUpResume() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Path log = workspace.resolve("metadata-child.log");
        Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                PersistenceFixture.class.getName(), workspace.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "元数据夹具必须在本地完成");
            assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "夹具清理必须终止直接进程");
            }
        }
        SessionStore store = new SessionStore(workspace);
        SessionStore.Session persisted = store.load(ID);
        assertEquals("COMPLETED", persisted.status());
        assertExactMetadata(persisted.transcript().getLast());
        List<ChatMessage> followUp = new ArrayList<>(persisted.transcript());
        followUp.add(ChatMessage.user("验证上一次答案，不编辑文件"));
        AtomicInteger requests = new AtomicInteger(), mutations = new AtomicInteger();
        Tool edit = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("edit", "修改哨兵", Map.of("type", "object")); }
            @Override public String execute(String args) { mutations.incrementAndGet(); return "changed"; }
        };
        AgentResult resumed;
        try (SessionStore.Lease ignored = store.lock(ID)) {
            resumed = new CodeAgent(request -> {
                requests.incrementAndGet();
                ChatMessage previous = request.messages().stream().filter(m -> m.content().equals(ANSWER)).findFirst().orElseThrow();
                assertExactMetadata(previous);
                assertEquals(followUp.getLast(), request.messages().getLast());
                return ChatResponse.text("已验证上一次答案");
            }, new ToolRegistry(List.of(edit))).resume(followUp, AgentRunOptions.defaults(), persistence(store));
            store.save(ID, persisted.provider(), persisted.model(), resumed.status().name(), resumed.transcript());
        }
        assertEquals(AgentResult.Status.COMPLETED, resumed.status(), resumed.error());
        assertEquals(1, requests.get());
        assertEquals(0, mutations.get());
        assertFalse(Files.exists(workspace.resolve("unexpected-mutation")));
        SessionStore.Session completed = new SessionStore(workspace).load(ID);
        assertEquals(resumed.transcript(), completed.transcript());
        assertExactMetadata(completed.transcript().stream().filter(m -> m.content().equals(ANSWER)).findFirst().orElseThrow());
        assertEquals("custom", completed.provider());
        assertEquals("fixture", completed.model());
    }

    private static void assertExactMetadata(ChatMessage message) {
        assertEquals(METADATA, message.providerMetadata());
        for (var entry : METADATA.entrySet()) assertArrayEquals(entry.getValue().getBytes(StandardCharsets.UTF_8),
                message.providerMetadata().get(entry.getKey()).getBytes(StandardCharsets.UTF_8));
    }

    private static AgentListener persistence(SessionStore store) {
        return new AgentListener() {
            @Override public void onCheckpoint(List<ChatMessage> transcript) {
                try { store.save(ID, "custom", "fixture", "RUNNING", transcript); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        };
    }

    public static final class PersistenceFixture {
        public static void main(String[] args) throws Exception {
            Path workspace = Path.of(args[0]);
            SessionStore store = new SessionStore(workspace);
            Tool sentinel = new Tool() {
                @Override public ToolDefinition definition() { return new ToolDefinition("edit", "修改哨兵", Map.of("type", "object")); }
                @Override public String execute(String arguments) throws Exception {
                    Files.writeString(workspace.resolve("unexpected-mutation"), "changed");
                    return "changed";
                }
            };
            try (SessionStore.Lease ignored = store.lock(ID)) {
                AgentResult result = new CodeAgent(request -> ChatResponse.text(ANSWER, METADATA), new ToolRegistry(List.of(sentinel)))
                        .run("返回最终答案，不进行修改", AgentRunOptions.defaults(), persistence(store));
                if (result.status() != AgentResult.Status.COMPLETED) throw new IllegalStateException(result.error());
                store.save(ID, "custom", "fixture", result.status().name(), result.transcript());
            }
        }
    }
}
