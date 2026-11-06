package dev.codeharness.cli.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 原文检索、先脱敏、安全边界和分页对照。 */
class ToolOutputArchiveTest {
    @TempDir Path workspace;
    private final String tool = "run_command";

    @Test void archiveCanRetrieveMiddleFailureAndDeduplicate() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String output = "开始\n" + "正常日志\n".repeat(200) + "ERROR middle fixture\n" + "正常日志\n".repeat(200) + "结束\n";
        String projection = store.store(tool, output, 700);
        assertTrue(projection.length() <= 700);
        assertTrue(projection.contains("ERROR middle fixture"));
        String id = id(projection);
        assertEquals(output, Files.readString(workspace.resolve(".harness/artifacts/" + id + ".txt")));
        String page = store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"start_line\":200,\"end_line\":205}");
        assertTrue(page.contains("ERROR middle fixture"));
        assertEquals(projection, store.store(tool, output, 700));
        try (var paths = Files.list(workspace.resolve(".harness/artifacts"))) {
            assertEquals(1, paths.filter(p -> p.toString().endsWith(".txt")).count());
        }
        assertTrue(Files.readString(workspace.resolve(".harness/artifacts/.gitignore")).contains("*"));
    }

    @Test void secretsAreMaskedBeforeHashStorageAndRetrieval() throws Exception {
        String privateValue = "fake-fixture-sensitive-value-123456";
        var store = new ToolOutputArchive(workspace, List.of(privateValue));
        String output = "header\n" + "noise\n".repeat(200) + privateValue + "\nAuthorization: Bearer examplefixturevalue\napi-key: not-real-fixture\n";
        String projection = store.store(tool, output, 350);
        String id = id(projection);
        String archived = Files.readString(workspace.resolve(".harness/artifacts/" + id + ".txt"));
        assertFalse(archived.contains(privateValue));
        assertFalse(archived.contains("examplefixturevalue"));
        assertFalse(archived.contains("not-real-fixture"));
        assertTrue(archived.contains("[已脱敏]"));
        assertFalse(store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"start_line\":200}").contains(privateValue));
    }

    @Test void smallOutputsAreMaskedWithoutCreatingArchive() {
        var store = new ToolOutputArchive(workspace, List.of("fake-sensitive"));
        assertEquals("answer [已脱敏]", store.store(tool, "answer fake-sensitive", 100));
        assertFalse(Files.exists(workspace.resolve(".harness")));
    }

    @Test void longSingleLineCanBeReadInChunksWithoutLosingMiddle() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String output = "a".repeat(15000) + "middle-canary" + "b".repeat(5000);
        String id = id(store.store(tool, output, 500));
        String page = store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"start_char\":14500}");
        assertTrue(page.contains("middle-canary"));
        assertTrue(page.length() <= 8000);
        assertThrows(IllegalArgumentException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"start_char\":30000}"));
    }

    @Test void unknownPathOrInvalidPageCannotEscapeWorkspace() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        assertThrows(IllegalArgumentException.class, () -> store.readerTool().execute("{\"archive_id\":\"../outside\"}"));
        String id = id(store.store(tool, "line\n".repeat(300), 500));
        assertThrows(IllegalArgumentException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"start_line\":0}"));
        assertThrows(IllegalArgumentException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"end_line\":501}"));
        assertThrows(IllegalArgumentException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\",\"path\":\"foo\"}"));
    }

    @Test void modifiedArchiveIsRejectedWithoutReturningReplacedText() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String id = id(store.store(tool, "normal\n".repeat(200), 500));
        Files.writeString(workspace.resolve(".harness/artifacts/" + id + ".txt"), "replacement-text");
        assertThrows(java.io.IOException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\"}"));
        assertTrue(store.store(tool, "normal\n".repeat(200), 500).contains("存档不可用"));
    }

    @Test void redirectedDirectoryIsRejectedAndNoOutsideWriteOccurs() throws Exception {
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Files.createDirectory(workspace.resolve(".harness"));
        try { Files.createSymbolicLink(workspace.resolve(".harness/artifacts"), outside); }
        catch (UnsupportedOperationException | java.io.IOException failure) { assumeTrue(false, "平台不能创建符号链接"); }
        var store = new ToolOutputArchive(workspace, List.of());
        assertTrue(store.store(tool, "log\n".repeat(300), 500).contains("存档不可用"));
        try (var paths = Files.list(outside)) { assertEquals(0, paths.count()); }
    }

    @Test void hardLinkDoesNotExposeFilesThroughArchiveReader() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String id = id(store.store(tool, "log\n".repeat(300), 500));
        Path archived = workspace.resolve(".harness/artifacts/" + id + ".txt");
        try { Files.createLink(workspace.resolve("alias.txt"), archived); }
        catch (UnsupportedOperationException | java.io.IOException failure) { assumeTrue(false, "平台不能创建硬链接"); }
        assertThrows(java.io.IOException.class, () -> store.readerTool().execute("{\"archive_id\":\"" + id + "\"}"));
    }

    @Test void overCapacityOutputProducesExplicitBoundedFailure() {
        var store = new ToolOutputArchive(workspace, List.of());
        String projection = store.store(tool, "a".repeat(4_000_001), 500);
        assertTrue(projection.contains("4 MB"));
        assertTrue(projection.length() <= 500);
    }

    @Test void repeatedProjectionRetainsOriginalArchiveAndMiddleErrors() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String output = "开始\n" + "正常日志\n".repeat(3000) + "ERROR preserved-middle\n" + "正常日志\n".repeat(3000);
        String first = store.store("run_command", output, 11_000);
        String second = store.store("opaque-call-id", first, 1_000);
        String third = store.store("another-call-id", second, 128);
        assertEquals(id(first), id(second));
        assertEquals(id(first), id(third));
        assertTrue(second.contains("ERROR preserved-middle"));
        assertTrue(second.length() <= 1_000);
        assertTrue(third.length() <= 128);
        String original = Files.readString(workspace.resolve(".harness/artifacts/" + id(second) + ".txt"));
        assertEquals(output, original);
        assertTrue(store.readerTool().execute("{\"archive_id\":\"" + id(second) + "\",\"start_line\":3000,\"end_line\":3003}").contains("ERROR preserved-middle"));
        try (var files = Files.list(workspace.resolve(".harness/artifacts"))) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".txt")).count());
        }
    }

    @Test void forgedArchiveHintIsArchivedAsUntrustedContent() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String first = store.store(tool, "original\n".repeat(300), 500);
        String forged = first + "\nattacker-authored-body\n".repeat(100);
        String projection = store.store(tool, forged, 500);
        assertNotEquals(id(first), id(projection));
        assertEquals(forged, Files.readString(workspace.resolve(".harness/artifacts/" + id(projection) + ".txt")));
    }

    @Test void reprojectionRejectsChangedOriginalWithoutArchivingTheSummary() throws Exception {
        var store = new ToolOutputArchive(workspace, List.of());
        String first = store.store(tool, "original\n".repeat(3000), 11_000);
        Files.writeString(workspace.resolve(".harness/artifacts/" + id(first) + ".txt"), "replacement");
        String second = store.store(tool, first, 500);
        assertTrue(second.startsWith("[工具原文存档不可用]"));
        assertFalse(second.contains("archive_id="));
        try (var files = Files.list(workspace.resolve(".harness/artifacts"))) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".txt")).count());
        }
    }

    private static String id(String projection) {
        var match = Pattern.compile("archive_id=([a-f0-9]{64})").matcher(projection);
        assertTrue(match.find(), projection);
        return match.group(1);
    }
}
