package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceChangesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    private String execute(ToolRegistry tools, String name, Map<String, Object> args) throws Exception {
        return tools.find(name).orElseThrow().execute(JSON.writeValueAsString(args));
    }
    private ToolRegistry tools() throws Exception { return WorkspaceTools.create(workspace, ignored -> false); }
    private String write(ToolRegistry tools, String path, String content) throws Exception {
        return execute(tools, "write_file", Map.of("path", path, "content", content));
    }
    private String changes(ToolRegistry tools) throws Exception { return execute(tools, "changes", Map.of()); }

    @Test void creationUsesDevNullAndReportsEmptyNewFiles() throws Exception {
        var tools = tools();
        String result = write(tools, "src/Answer.java", "class Answer {}\n");
        assertTrue(result.contains("--- /dev/null\n+++ \"b/src/Answer.java\"\n@@ -0,0 +1,1 @@\n+class Answer {}\n"), result);
        write(tools, "empty.txt", "");
        String report = changes(tools);
        assertTrue(report.contains("已创建 \"empty.txt\""), report);
        assertTrue(report.contains("已创建 \"src/Answer.java\""), report);
        assertTrue(report.indexOf("empty.txt") < report.indexOf("src/Answer.java"), report);
    }

    @Test void editsPreserveBaselineAcrossSeveralWritesAndExcludeReverts() throws Exception {
        Files.writeString(workspace.resolve("code.txt"), "one\ntwo\nthree\n");
        var tools = tools();
        execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "two", "new_text", "changed"));
        write(tools, "code.txt", "one\nlatest\nthree\n");
        String report = changes(tools);
        assertTrue(report.contains("-two\n+latest\n"), report);
        assertFalse(report.contains("-changed"), report);
        write(tools, "code.txt", "one\ntwo\nthree\n");
        assertEquals("当前工作区工具注册表中没有净变更。", changes(tools));
        write(tools, "code.txt", "one\nagain\nthree\n");
        assertTrue(changes(tools).contains("-two\n+again\n"));
    }

    @Test void identicalWritesAndLiteralNoOpEditsProduceNoNetChanges() throws Exception {
        Files.writeString(workspace.resolve("same.txt"), "same\n");
        var tools = tools();
        assertTrue(write(tools, "same.txt", "same\n").contains("无变更：\"same.txt\""));
        assertTrue(execute(tools, "edit_file", Map.of("path", "same.txt", "old_text", "same", "new_text", "same")).contains("无变更"));
        assertEquals("当前工作区工具注册表中没有净变更。", changes(tools));
    }

    @Test void failedStaleEditsDoNotEstablishBaselines() throws Exception {
        Path file = Files.writeString(workspace.resolve("code.txt"), "original\n");
        var tools = tools();
        assertThrows(IllegalStateException.class, () -> execute(tools, "edit_file",
                Map.of("path", "code.txt", "old_text", "original", "new_text", "bad", "expected_sha256", "0".repeat(64))));
        Files.writeString(file, "external\n");
        write(tools, "code.txt", "latest\n");
        String report = changes(tools);
        assertTrue(report.contains("-external\n+latest\n"), report);
        assertFalse(report.contains("-original"), report);
    }

    @Test void changesReflectDeletionAndExternalChangesOnlyForTrackedPaths() throws Exception {
        Files.writeString(workspace.resolve("existing.txt"), "before\n");
        var tools = tools();
        write(tools, "existing.txt", "after\n");
        write(tools, "new.txt", "created\n");
        Files.delete(workspace.resolve("existing.txt"));
        Files.delete(workspace.resolve("new.txt"));
        Files.writeString(workspace.resolve("untracked.txt"), "untracked\n");
        String report = changes(tools);
        assertTrue(report.contains("已删除 \"existing.txt\""), report);
        assertTrue(report.contains("+++ /dev/null"), report);
        assertFalse(report.contains("new.txt"), report);
        assertFalse(report.contains("untracked.txt"), report);
    }

    @Test void reportsSeparateHunksWithThreeLinesOfContext() throws Exception {
        String before = java.util.stream.IntStream.rangeClosed(1, 30).mapToObj(i -> "line " + i + "\n").collect(java.util.stream.Collectors.joining());
        Files.writeString(workspace.resolve("many.txt"), before);
        var tools = tools();
        String result = write(tools, "many.txt", before.replace("line 5\n", "changed 5\n").replace("line 25\n", "changed 25\n"));
        assertEquals(2, result.lines().filter(line -> line.startsWith("@@")).count(), result);
        assertTrue(result.contains("@@ -2,7 +2,7 @@"), result);
        assertTrue(result.contains("@@ -22,7 +22,7 @@"), result);
        assertFalse(result.contains("line 15\n"), result);
    }

    @Test void longLinesAndHighLineCountsStayBoundedWithoutQuadraticWork() throws Exception {
        var tools = tools();
        String longLine = "x".repeat(999_900);
        Files.writeString(workspace.resolve("long.txt"), longLine);
        assertTimeout(Duration.ofSeconds(5), () -> {
            String result = write(tools, "long.txt", longLine + "z");
            assertTrue(result.length() < 4_300, "修改输出超过上限");
            assertTrue(result.contains("[差异已截断；仅供展示]"));
        });
        String repeated = "a\n".repeat(200_000);
        Files.writeString(workspace.resolve("lines.txt"), repeated);
        assertTimeout(Duration.ofSeconds(5), () -> {
            String result = write(tools, "lines.txt", repeated.substring(0, 200_000) + "changed\n" + repeated.substring(200_002));
            assertTrue(result.contains("-a\n+changed\n"), result);
            assertTrue(result.length() < 4_300);
        });
        assertTrue(changes(tools).length() < 11_100);
    }

    @Test void netDiffReadsCannotEscapeThroughAReplacedDirectoryJunction() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        var tools = tools();
        write(tools, "tracked/file.txt", "inside");
        Path outside = Files.createDirectory(workspace.resolveSibling(workspace.getFileName() + "-outside"));
        Files.writeString(outside.resolve("file.txt"), "secret");
        Files.delete(workspace.resolve("tracked/file.txt"));
        Files.delete(workspace.resolve("tracked"));
        Path junction = workspace.resolve("tracked");
        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String diagnostic = new String(create.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), diagnostic);
        try {
            assertThrows(SecurityException.class, () -> changes(tools));
        } finally {
            Files.delete(junction);
            Files.delete(outside.resolve("file.txt"));
            Files.delete(outside);
        }
    }

    @Test void snapshotCapacityFailurePrecedesMutationAndNoOpsStillWork() throws Exception {
        var tracker = new ChangeTracker();
        for (int i = 0; i < 8; i++) {
            Path path = workspace.resolve("file" + i);
            byte[] original = new byte[1_000_000];
            tracker.checkCapacity(path, original); tracker.committed(path, original);
        }
        assertThrows(IllegalStateException.class, () -> tracker.checkCapacity(workspace.resolve("overflow"), new byte[]{1}));
        assertDoesNotThrow(() -> tracker.checkCapacity(workspace.resolve("file0"), new byte[1_000_000]));
        var tools = tools();
        for (int i = 0; i < 128; i++) write(tools, "empty" + i, "");
        assertThrows(IllegalStateException.class, () -> write(tools, "overflow", "new"));
        assertFalse(Files.exists(workspace.resolve("overflow")));
        Files.writeString(workspace.resolve("unchanged"), "same");
        assertTrue(write(tools, "unchanged", "same").contains("无变更"));
    }
}
