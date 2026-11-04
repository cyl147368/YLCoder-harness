package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 日志列表只能读取选定工作区中的普通文件，查看操作不能创建诊断状态。 */
class TraceFilesTest {
    @TempDir Path temporary;

    @Test void missingLogsReturnEmptyWithoutCreatingDirectories() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        assertEquals(workspace.toRealPath().resolve(".harness/logs"), TraceFiles.directory(workspace));
        assertTrue(TraceFiles.recent(workspace, null, 10).isEmpty());
        assertTrue(TraceFiles.recent(workspace, "missing-session", 10).isEmpty());
        assertFalse(Files.exists(workspace.resolve(".harness")));
    }

    @Test void newestFilesAreListedAcrossSessionsAndFilteredBySession() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path oldest = log(workspace, "session-a", "old.md", 1_000);
        Path middle = log(workspace, "session-b", "middle.md", 2_000);
        Path newest = log(workspace, "session-a", "new.md", 3_000);
        Path invalid = log(workspace, "invalid.session", "ignored.md", 4_000);
        Files.writeString(newest.getParent().resolve("not-a-log.json"), "不得列出");
        Files.createDirectory(newest.getParent().resolve("directory.md"));
        assertEquals(List.of(newest, middle), TraceFiles.recent(workspace, null, 2));
        assertEquals(List.of(newest, oldest), TraceFiles.recent(workspace, "session-a", 10));
        assertEquals(List.of(middle), TraceFiles.recent(workspace, "session-b", 10));
        assertEquals("诊断夹具", Files.readString(invalid));
    }

    @Test void equalTimestampsHaveDeterministicOrdering() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path a = log(workspace, "session-a", "a.md", 1_000);
        Path b = log(workspace, "session-a", "b.md", 1_000);
        assertEquals(List.of(b, a), TraceFiles.recent(workspace, null, 10));
        assertEquals(List.of(b), TraceFiles.recent(workspace, null, 1));
    }

    @Test void invalidSessionIdsAndLimitsFailBeforeStateIsCreated() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        for (String id : List.of("", "../outside", "a/b", "a\\b", "a:b", "a".repeat(81)))
            assertThrows(IOException.class, () -> TraceFiles.recent(workspace, id, 10));
        for (int limit : List.of(0, -1, 101))
            assertThrows(IllegalArgumentException.class, () -> TraceFiles.recent(workspace, null, limit));
        assertFalse(Files.exists(workspace.resolve(".harness")));
    }

    @Test void blockingStateOrLogsFileIsPreservedAndReported() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = Files.writeString(workspace.resolve(".harness"), "原有文件");
        assertThrows(IOException.class, () -> TraceFiles.recent(workspace, null, 10));
        assertEquals("原有文件", Files.readString(state));
        Files.delete(state);
        Files.createDirectory(state);
        Path logs = Files.writeString(state.resolve("logs"), "保留日志路径占位");
        assertThrows(IOException.class, () -> TraceFiles.recent(workspace, null, 10));
        assertEquals("保留日志路径占位", Files.readString(logs));
    }

    @Test void linkedSessionAndLogCannotExposeAnotherDirectory() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path own = log(workspace, "owned-session", "own.md", 1_000);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path privateLog = Files.writeString(outside.resolve("private.md"), "工作区外内容");
        symlinkOrSkip(workspace.resolve(".harness/logs/linked-session"), outside);
        symlinkOrSkip(own.getParent().resolve("linked.md"), privateLog);
        assertEquals(List.of(own), TraceFiles.recent(workspace, null, 10));
        assertThrows(IOException.class, () -> TraceFiles.recent(workspace, "linked-session", 10));
        assertEquals("工作区外内容", Files.readString(privateLog));
    }

    @Test void linkedLogsRootIsRejected() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectory(workspace.resolve(".harness"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        symlinkOrSkip(workspace.resolve(".harness/logs"), outside);
        assertThrows(IOException.class, () -> TraceFiles.recent(workspace, null, 10));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    private static Path log(Path workspace, String session, String name, long time) throws Exception {
        Path directory = Files.createDirectories(workspace.toRealPath().resolve(".harness/logs").resolve(session));
        Path file = Files.writeString(directory.resolve(name), "诊断夹具");
        Files.setLastModifiedTime(file, FileTime.fromMillis(time));
        return file;
    }

    private static void symlinkOrSkip(Path link, Path destination) {
        try { Files.createSymbolicLink(link, destination); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.abort("当前环境不能建立符号链接：" + unavailable.getClass().getSimpleName());
        }
    }
}
