package dev.codeharness.cli;

import dev.codeharness.core.agent.*;
import dev.codeharness.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextSessionStoreTest {
    @TempDir Path workspace;
    @Test void storesSummaryWithOriginalAndPreservesOnAppendButClearsOnRewind() throws Exception {
        var store = new SessionStore(workspace); String id = store.newId();
        var h = List.of(ChatMessage.system("规则"), ChatMessage.user("任务"), ChatMessage.assistant("原始历史"), ChatMessage.user("继续"));
        var summary = new ContextSummary(List.of("任务"), List.of("约束"), List.of(), List.of(), List.of(), List.of(), List.of());
        var snapshot = new ContextSnapshot(1, 3, ContextManager.fingerprint(h.subList(0, 3)), summary);
        store.save(id, "custom", "model", "RUNNING", h); store.saveContext(id, snapshot);
        var resumed = new SessionStore(workspace).load(id);
        assertEquals(h, resumed.transcript()); assertEquals(snapshot, resumed.context());
        var next = new ArrayList<>(h); next.add(ChatMessage.assistant("完成"));
        store.save(id, "custom", "other-model", "COMPLETED", next);
        assertEquals(snapshot, store.load(id).context());
        store.rewind(id, "custom", "other-model", h.subList(0, 2));
        assertNull(store.load(id).context()); assertEquals(1, store.load(id).rewinds().size());
    }
    @Test void rejectsMismatchedSnapshotAndLoadsOldFormat() throws Exception {
        var store = new SessionStore(workspace); String id = store.newId(); var h = List.of(ChatMessage.user("任务"));
        store.save(id, "custom", "model", "READY", h);
        assertNull(store.load(id).context());
        var summary = new ContextSummary(List.of("任务"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(java.io.IOException.class, () -> store.saveContext(id, new ContextSnapshot(1, 1, "a".repeat(64), summary)));
        assertEquals(h, store.load(id).transcript());
    }
}
