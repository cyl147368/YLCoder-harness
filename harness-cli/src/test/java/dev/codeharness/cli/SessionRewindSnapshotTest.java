package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 活动历史可回退，旧记录仍保存在同一检查点中。 */
class SessionRewindSnapshotTest {
    @TempDir Path workspace;

    @Test void 多次回退和后续保存保留旧历史且不增加会话() throws Exception {
        var store = new SessionStore(workspace);
        var first = List.of(ChatMessage.user("旧问题"), ChatMessage.assistant("旧回答"));
        var second = List.of(ChatMessage.user("新问题"), ChatMessage.assistant("新回答"));
        try (var lease = store.lock("same")) {
            store.save("same", "openai-compatible", "fixture", "COMPLETED", first);
            store.rewind("same", "openai-compatible", "fixture", List.of());
            store.save("same", "openai-compatible", "fixture", "COMPLETED", second);
            store.rewind("same", "openai-compatible", "fixture", List.of());
        }
        var restored = new SessionStore(workspace).load("same");
        assertTrue(restored.transcript().isEmpty());
        assertEquals(List.of(first, second), restored.rewinds().stream().map(SessionStore.RewindSnapshot::transcript).toList());
        assertEquals(List.of("same"), store.recent(20).stream().map(SessionStore.Summary::id).toList());
    }

    @Test void 没有快照字段的旧检查点仍可加载() throws Exception {
        var store = new SessionStore(workspace);
        store.save("old", "openai-compatible", "fixture", "COMPLETED", List.of(ChatMessage.user("旧格式")));
        var file = workspace.resolve(".harness/sessions/old.json");
        var json = new ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(file));
        root.remove("rewinds");
        Files.write(file, json.writeValueAsBytes(root));
        assertTrue(store.load("old").rewinds().isEmpty());
        assertEquals("旧格式", store.load("old").transcript().getFirst().content());
    }

    @Test void 快照超过容量时原记录和会话锁保持有效且仍能继续保存() throws Exception {
        var store = new SessionStore(workspace);
        var first = List.of(ChatMessage.user("a".repeat(16_100_000)));
        var second = List.of(ChatMessage.user("b".repeat(16_100_000)));
        var file = workspace.resolve(".harness/sessions/same.json");
        try (var lease = store.lock("same")) {
            store.save("same", "openai-compatible", "fixture", "COMPLETED", first);
            byte[] original = Files.readAllBytes(file);
            assertThrows(java.io.IOException.class,
                    () -> store.rewind("same", "openai-compatible", "fixture", second));
            assertArrayEquals(original, Files.readAllBytes(file));
            assertTrue(lease.lock().isValid());
            assertThrows(java.io.IOException.class, () -> new SessionStore(workspace).lock("same"));
            assertTrue(store.load("same").rewinds().isEmpty());
            store.save("same", "openai-compatible", "fixture", "COMPLETED", List.of(ChatMessage.user("继续")));
            assertEquals("继续", store.load("same").transcript().getFirst().content());
        }
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
