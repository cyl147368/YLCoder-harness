package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SessionStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    private Path workspace() throws IOException { return Files.createDirectories(temporary.resolve("workspace")); }
    private static void save(SessionStore store, String text) throws IOException {
        store.save("session", "custom", "model", "RUNNING", List.of(ChatMessage.user(text)));
    }
    private static Path checkpoint(Path workspace) { return workspace.resolve(".harness/sessions/session.json"); }

    @Test void roundTripsStandaloneAndLeasedSaves() throws Exception {
        SessionStore store = new SessionStore(workspace());
        save(store, "first");
        assertEquals("first", store.load("session").transcript().getFirst().content());
        try (SessionStore.Lease ignored = store.lock("session")) { save(store, "second"); }
        assertEquals("second", store.load("session").transcript().getFirst().content());
    }

    @Test void lockPreventsOtherStoresAndThreadsFromSaving() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root), other = new SessionStore(root);
        try (SessionStore.Lease ignored = store.lock("session")) {
            assertThrows(IOException.class, () -> other.lock("session"));
            assertThrows(IOException.class, () -> save(other, "不允许"));
            assertThrows(IOException.class, () -> store.lock("session"));
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertTrue(executor.submit(() -> {
                    try { save(store, "错误线程"); return false; }
                    catch (IOException expected) { return true; }
                }).get());
            }
            save(store, "owner");
        }
        try (SessionStore.Lease lease = other.lock("session")) { assertTrue(lease.lock().isValid()); }
        assertEquals("owner", store.load("session").transcript().getFirst().content());
    }

    @Test void closingLeaseTwiceIsSafeAndAllowsReacquisition() throws Exception {
        SessionStore store = new SessionStore(workspace());
        SessionStore.Lease lease = store.lock("session");
        lease.close();
        lease.close();
        save(store, "reacquired");
    }

    @Test void rejectsInvalidIdsBeforeCreatingFiles() throws Exception {
        SessionStore store = new SessionStore(workspace());
        for (String id : new String[]{"../outside", "a/b", "", "a".repeat(81), "a:b"}) {
            assertThrows(IOException.class, () -> store.load(id));
            assertThrows(IOException.class, () -> store.lock(id));
            assertThrows(IOException.class, () -> store.save(id, "custom", "model", "RUNNING", List.of()));
        }
    }

    @Test void rejectsMalformedCheckpointAndIdentityMismatches() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        save(store, "content");
        String valid = Files.readString(checkpoint(root));
        for (String invalid : new String[]{"null", "{", valid + " {}", valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"id\":\"session\"", "\"id\":\"different\""),
                valid.replace("\"status\":\"RUNNING\"", "\"status\":null"),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1")}) {
            Files.writeString(checkpoint(root), invalid);
            assertThrows(IOException.class, () -> store.load("session"));
        }
        var node = JSON.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("workspace", temporary.toString());
        Files.writeString(checkpoint(root), node.toString());
        assertThrows(IOException.class, () -> store.load("session"));
    }

    @Test void oversizeSaveLeavesExistingCheckpointAndCleansTemporaryFile() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        save(store, "original");
        byte[] original = Files.readAllBytes(checkpoint(root));
        assertThrows(IOException.class, () -> save(store, "x".repeat(32_000_000)));
        assertArrayEquals(original, Files.readAllBytes(checkpoint(root)));
        assertEquals("original", store.load("session").transcript().getFirst().content());
        try (var files = Files.list(checkpoint(root).getParent())) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test void oversizedLoadIsRejectedBeforeParsing() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        try (FileChannel channel = FileChannel.open(checkpoint(root), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(32_000_000);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        IOException failure = assertThrows(IOException.class, () -> store.load("session"));
        assertTrue(failure.getMessage().contains("32 MB"));
    }

    @Test void abandonedTemporaryFileDoesNotOverrideLastCheckpoint() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        save(store, "committed");
        Path abandoned = Files.writeString(checkpoint(root).getParent().resolve("checkpoint-abandoned.tmp"), "{unfinished");
        assertEquals("committed", new SessionStore(root).load("session").transcript().getFirst().content());
        assertTrue(Files.exists(abandoned));
    }

    @Test void rejectsInvalidSaveWithoutReplacingCheckpoint() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        save(store, "keep");
        assertThrows(IOException.class, () -> store.save("session", null, "model", "RUNNING", List.of()));
        assertThrows(IOException.class, () -> store.save("session", "custom", "model", "RUNNING", null));
        assertEquals("keep", store.load("session").transcript().getFirst().content());
    }

    @Test void rejectsMalformedToolEvidenceOnLoadAndSaveWithoutReplacingValidCheckpoint() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        save(store, "keep");
        byte[] original = Files.readAllBytes(checkpoint(root));
        SessionStore.Session valid = store.load("session");
        ToolCall call = new ToolCall("private-call-id", "edit", "{}");
        ChatMessage assistant = ChatMessage.assistantToolCalls("", List.of(call));
        List<List<ChatMessage>> malformed = List.of(
                List.of(ChatMessage.user("task"), ChatMessage.toolResult(call, "orphan-secret")),
                List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call, call))),
                List.of(ChatMessage.user("task"), assistant, ChatMessage.toolResult(call, "first"), ChatMessage.toolResult(call, "second")),
                List.of(ChatMessage.user("task"), assistant, ChatMessage.toolResult(new ToolCall("other", "edit", "{}"), "unmatched")),
                List.of(ChatMessage.user("task"), assistant,
                        new ChatMessage(ChatMessage.Role.TOOL, "wrong", List.of(), call.id(), "other-tool")));
        for (List<ChatMessage> history : malformed) {
            IOException rejectedSave = assertThrows(IOException.class,
                    () -> store.save("session", "custom", "model", "RUNNING", history));
            assertEquals("会话工具历史无效", rejectedSave.getMessage());
            assertNull(rejectedSave.getCause(), "不得在嵌套错误中暴露持久化的工具内容");
            assertArrayEquals(original, Files.readAllBytes(checkpoint(root)));
            SessionStore.Session raw = new SessionStore.Session(valid.version(), valid.id(), valid.workspace(),
                    valid.provider(), valid.model(), valid.status(), valid.updatedAt(), history);
            // 绕过保存验证，复现外部损坏但 JSON 语法合法的证据。
            Files.write(checkpoint(root), JSON.writeValueAsBytes(raw));
            IOException rejectedLoad = assertThrows(IOException.class, () -> store.load("session"));
            assertEquals("会话工具历史无效", rejectedLoad.getMessage());
            assertNull(rejectedLoad.getCause());
            Files.write(checkpoint(root), original);
        }
        try (var files = Files.list(checkpoint(root).getParent())) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test void pendingAndPartiallyCompletedToolCheckpointsRemainLoadable() throws Exception {
        SessionStore store = new SessionStore(workspace());
        ToolCall first = new ToolCall("first", "edit", "{}"), second = new ToolCall("second", "edit", "{}");
        ChatMessage assistant = ChatMessage.assistantToolCalls("editing", List.of(first, second));
        for (List<ChatMessage> history : List.of(List.of(ChatMessage.user("task"), assistant),
                List.of(ChatMessage.user("task"), assistant, ChatMessage.toolResult(first, "saved")))) {
            store.save("session", "custom", "model", "RUNNING", history);
            assertEquals(history, store.load("session").transcript());
        }
    }

    @Test void rejectsHarnessJunctionBeforeCreatingAnythingOutsideWorkspace() throws Exception {
        assumeTrue(windows(), "Windows junction 测试");
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("external-state"));
        Path link = root.resolve(".harness");
        createJunction(link, outside);
        try {
            assertThrows(IOException.class, () -> new SessionStore(root));
            assertFalse(Files.exists(outside.resolve("sessions")));
        } finally { Files.deleteIfExists(link); }
    }

    @Test void rejectsSessionsJunctionAndDirectoryReplacement() throws Exception {
        assumeTrue(windows(), "Windows junction 测试");
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        Path outside = Files.createDirectory(temporary.resolve("external-sessions"));
        Path directory = root.resolve(".harness/sessions");
        Files.delete(directory);
        createJunction(directory, outside);
        try {
            assertThrows(IOException.class, () -> new SessionStore(root));
            assertThrows(IOException.class, () -> store.lock("session"));
            assertThrows(IOException.class, () -> save(store, "escape"));
            assertThrows(IOException.class, () -> store.load("session"));
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        } finally { Files.deleteIfExists(directory); }
    }

    @Test void rejectsSymlinkCheckpointAndLockFile() throws Exception {
        Path root = workspace();
        SessionStore store = new SessionStore(root);
        Path outside = Files.writeString(temporary.resolve("outside"), "keep");
        Path link = checkpoint(root);
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "符号链接不可用"); }
        assertThrows(IOException.class, () -> store.load("session"));
        assertThrows(IOException.class, () -> save(store, "escape"));
        Files.delete(link);
        Files.createSymbolicLink(link.resolveSibling("session.lock"), outside);
        assertThrows(IOException.class, () -> store.lock("session"));
        assertEquals("keep", Files.readString(outside));
    }

    @Test void checkpointsAndLocksUsePrivatePosixPermissions() throws Exception {
        Path root = workspace();
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX 权限不可用");
        Path state = Files.createDirectory(root.resolve(".harness"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxrwxrwx"));
        SessionStore store = new SessionStore(root);
        save(store, "private");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.resolve("sessions"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(checkpoint(root))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.resolve("sessions/session.lock"))));
    }

    private static void createJunction(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
