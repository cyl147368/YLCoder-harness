package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    private ToolRegistry tools() throws Exception { return WorkspaceTools.create(temporary.resolve("workspace"), ignored -> false); }

    private String execute(ToolRegistry tools, String name, Map<String, Object> arguments) throws Exception {
        return tools.find(name).orElseThrow().execute(JSON.writeValueAsString(arguments));
    }

    @Test void readsRangesAndAtomicallyReplacesFiles() throws Exception {
        ToolRegistry tools = tools();
        execute(tools, "write_file", Map.of("path", "src/example.txt", "content", "one\ntwo\nthree"));
        assertEquals("2: two\n", execute(tools, "read_file", Map.of("path", "src/example.txt", "start_line", 2, "end_line", 2)));
        execute(tools, "write_file", Map.of("path", "src/example.txt", "content", "replacement"));
        assertEquals("replacement", Files.readString(temporary.resolve("workspace/src/example.txt")));
        try (var files = Files.list(temporary.resolve("workspace/src"))) {
            assertEquals(1, files.count());
        }
    }

    @Test void rejectsTraversalAndOutsideAbsolutePaths() throws Exception {
        ToolRegistry tools = tools();
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "private");
        assertThrows(SecurityException.class, () -> execute(tools, "read_file", Map.of("path", "../outside.txt")));
        assertThrows(SecurityException.class, () -> execute(tools, "read_file", Map.of("path", outside.toString())));
        assertThrows(SecurityException.class, () -> execute(tools, "write_file", Map.of("path", "../new.txt", "content", "bad")));
        assertFalse(Files.exists(temporary.resolve("new.txt")));
    }

    @Test void doesNotReadOrSearchOutsideSymlinks() throws Exception {
        ToolRegistry tools = tools();
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "secret-needle");
        Path link = temporary.resolve("workspace/link.txt");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前平台未授予创建符号链接的权限：" + e.getMessage());
        }
        assertThrows(SecurityException.class, () -> execute(tools, "read_file", Map.of("path", "link.txt")));
        assertThrows(SecurityException.class, () -> execute(tools, "write_file", Map.of("path", "link.txt", "content", "bad")));
        assertEquals("", execute(tools, "search_files", Map.of("query", "secret-needle")));
        assertEquals("secret-needle", Files.readString(outside));
    }

    @Test void refusesWritingThroughEscapingDirectorySymlinks() throws Exception {
        ToolRegistry tools = tools();
        Path outside = Files.createDirectory(temporary.resolve("outside-dir"));
        try { Files.createSymbolicLink(temporary.resolve("workspace/linked"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前平台未授予创建符号链接的权限：" + e.getMessage());
        }
        assertThrows(SecurityException.class, () -> execute(tools, "write_file", Map.of("path", "linked/new.txt", "content", "bad")));
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test void searchSkipsBinaryFilesAndExcludedDirectories() throws Exception {
        ToolRegistry tools = tools();
        Path workspace = temporary.resolve("workspace");
        Files.write(workspace.resolve("binary.dat"), new byte[]{(byte) 0xff, (byte) 0xfe});
        Files.writeString(workspace.resolve("nul.dat"), "needle\0binary");
        Files.writeString(workspace.resolve("code.java"), "a\nneedle\n");
        Files.createDirectories(workspace.resolve("node_modules/nested"));
        Files.writeString(workspace.resolve("node_modules/nested/dependency.js"), "needle");
        assertEquals("code.java:2: needle\n", execute(tools, "search_files", Map.of("query", "needle")));
        assertFalse(execute(tools, "list_files", Map.of()).contains("node_modules"));
    }

    @Test void windowsJunctionsCannotEscapeWorkspace() throws Exception {
        assumeTrue(windows(), "Windows junction 测试");
        ToolRegistry tools = tools();
        Path outside = Files.createDirectory(temporary.resolve("outside-junction"));
        Files.writeString(outside.resolve("outside.txt"), "secret-needle");
        Path junction = temporary.resolve("workspace/junction");
        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String diagnostic = new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), diagnostic);
        try {
            assertThrows(SecurityException.class, () -> execute(tools, "read_file", Map.of("path", "junction/outside.txt")));
            assertThrows(SecurityException.class, () -> execute(tools, "write_file", Map.of("path", "junction/new.txt", "content", "bad")));
            assertThrows(SecurityException.class, () -> execute(tools, "edit_file", Map.of("path", "junction/outside.txt", "old_text", "secret", "new_text", "bad")));
            assertEquals("", execute(tools, "search_files", Map.of("query", "secret-needle")));
            assertFalse(execute(tools, "list_files", Map.of()).contains("outside.txt"));
            assertFalse(Files.exists(outside.resolve("new.txt")));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test void editsUniqueLiteralTextWithHashGuardAndConciseResult() throws Exception {
        ToolRegistry tools = tools();
        Path file = Files.writeString(temporary.resolve("workspace/code.java"), "first\r\nreturn \"$1 \\ text\";\r\nlast\r\n");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String result = execute(tools, "edit_file", Map.of("path", "code.java", "old_text", "$1 \\ text", "new_text", "中文 ✅", "expected_sha256", expected));
        assertEquals("first\r\nreturn \"中文 ✅\";\r\nlast\r\n", Files.readString(file));
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertTrue(result.contains("sha256=" + actual), result);
        assertTrue(result.contains("已替换 1 处字面匹配"));
        assertTrue(result.contains("--- \"a/code.java\""));
        assertTrue(result.contains("-return \"$1 \\ text\";"));
        assertTrue(result.contains("+return \"中文 ✅\";"));
        assertTrue(result.length() < 600);
    }

    @Test void rejectsStaleOrAmbiguousEditsWithoutChangingFiles() throws Exception {
        ToolRegistry tools = tools();
        Path file = Files.writeString(temporary.resolve("workspace/code.txt"), "value value aaa");
        assertThrows(IllegalStateException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "missing", "new_text", "new")));
        assertThrows(IllegalStateException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "value", "new_text", "new")));
        assertThrows(IllegalStateException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "aa", "new_text", "new")));
        assertThrows(IllegalStateException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "aaa", "new_text", "new", "expected_sha256", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "", "new_text", "new")));
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "aaa", "new_text", "new", "expected_sha256", "")));
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "edit_file", Map.of("path", "code.txt", "old_text", "aaa")));
        assertEquals("value value aaa", Files.readString(file));
    }

    @Test void editRejectsTraversalAndSymlinkTargets() throws Exception {
        ToolRegistry tools = tools();
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "keep");
        assertThrows(SecurityException.class, () -> execute(tools, "edit_file", Map.of("path", "../outside.txt", "old_text", "keep", "new_text", "bad")));
        assertThrows(SecurityException.class, () -> execute(tools, "edit_file", Map.of("path", outside.toString(), "old_text", "keep", "new_text", "bad")));
        assertEquals("keep", Files.readString(outside));
        Path target = Files.writeString(temporary.resolve("workspace/target.txt"), "keep");
        try { Files.createSymbolicLink(temporary.resolve("workspace/link.txt"), target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前平台未授予创建符号链接的权限：" + e.getMessage());
        }
        assertThrows(SecurityException.class, () -> execute(tools, "edit_file", Map.of("path", "link.txt", "old_text", "keep", "new_text", "bad")));
        assertEquals("keep", Files.readString(target));
    }

    @Test void editsValidateUtf8AndSizeLimits() throws Exception {
        ToolRegistry tools = tools();
        Path invalid = Files.write(temporary.resolve("workspace/invalid.txt"), new byte[]{(byte) 0xc3, 0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> execute(tools, "edit_file", Map.of("path", "invalid.txt", "old_text", "(", "new_text", "new")));
        assertArrayEquals(new byte[]{(byte) 0xc3, 0x28}, Files.readAllBytes(invalid));
        Path file = Files.writeString(temporary.resolve("workspace/limit.txt"), "keep");
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "edit_file", Map.of("path", "limit.txt", "old_text", "keep", "new_text", "x".repeat(1_000_001))));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> execute(tools, "edit_file", Map.of("path", "limit.txt", "old_text", "keep", "new_text", "\ud800")));
        assertEquals("keep", Files.readString(file));
        Files.writeString(file, "a".repeat(1_000_001));
        assertThrows(SecurityException.class, () -> execute(tools, "edit_file", Map.of("path", "limit.txt", "old_text", "a", "new_text", "b")));
    }

    @Test void editResultIsBoundedEvenForLargeReplacement() throws Exception {
        ToolRegistry tools = tools();
        Path file = Files.writeString(temporary.resolve("workspace/large-edit.txt"), "keep");
        String replacement = "x".repeat(200_000);
        String result = execute(tools, "edit_file", Map.of("path", "large-edit.txt", "old_text", "keep", "new_text", replacement));
        assertTrue(result.length() < 4_300);
        assertTrue(result.contains("[差异已截断；仅供展示]"));
        assertEquals(replacement, Files.readString(file));
    }

    @Test void atomicWritesAndEditsPreservePosixExecutableBits() throws Exception {
        ToolRegistry tools = tools();
        Path file = Files.writeString(temporary.resolve("workspace/script.sh"), "echo old\n");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"), "POSIX 权限测试");
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE);
        Files.setPosixFilePermissions(file, permissions);
        execute(tools, "write_file", Map.of("path", "script.sh", "content", "echo new\n"));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
        execute(tools, "edit_file", Map.of("path", "script.sh", "old_text", "new", "new_text", "latest"));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test void boundsLongFileAndSearchOutput() throws Exception {
        ToolRegistry tools = tools();
        Files.writeString(temporary.resolve("workspace/large.txt"), "needle" + "x".repeat(200_000));
        for (String result : new String[]{execute(tools, "read_file", Map.of("path", "large.txt")),
                execute(tools, "search_files", Map.of("query", "needle"))}) {
            assertTrue(result.length() < 11_100);
            assertTrue(result.endsWith("[输出已截断]"));
        }
    }

    @Test void invalidArgumentsCannotSilentlyOverwriteContent() throws Exception {
        ToolRegistry tools = tools();
        Files.writeString(temporary.resolve("workspace/keep.txt"), "keep");
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "write_file", Map.of("path", "keep.txt")));
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "read_file", Map.of("path", "keep.txt", "start_line", 0)));
        assertThrows(IllegalArgumentException.class, () -> tools.find("read_file").orElseThrow().execute("null"));
        assertEquals("keep", Files.readString(temporary.resolve("workspace/keep.txt")));
    }

    @Test void everyShellCommandRequiresApproval() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry tools = WorkspaceTools.create(temporary.resolve("workspace"), prompt -> { calls.incrementAndGet(); return false; });
        assertThrows(SecurityException.class, () -> execute(tools, "run_command", Map.of("command", "echo harmless")));
        assertEquals(1, calls.get());
    }

    @Test void structuredApprovalExecutesTheApprovedArgvWithoutReplayingShellText() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("direct workspace"));
        AtomicInteger calls = new AtomicInteger();
        Path java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        ToolRegistry tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            calls.incrementAndGet();
            assertEquals(root.toAbsolutePath().normalize(), request.workspace());
            assertEquals("echo unexpected > unexpected.txt", request.command());
            return CommandApproval.Decision.direct(List.of(java.toString(), "-version"));
        });
        String result = execute(tools, "run_command", Map.of("command", "echo unexpected > unexpected.txt"));
        assertEquals(1, calls.get());
        assertTrue(result.startsWith("退出码：0"), result);
        assertTrue(result.contains("version"), result);
        assertFalse(Files.exists(root.resolve("unexpected.txt")), "结构化批准不能回到原始 shell 命令");
    }

    @Test void readRangesRejectCoercionAndIntegerOverflow() throws Exception {
        ToolRegistry tools = tools();
        Files.writeString(temporary.resolve("workspace/lines.txt"), "one\ntwo");
        for (String field : new String[]{"start_line", "end_line"}) {
            for (String value : new String[]{"null", "true", "1.5", "\"2\"", "2147483648", "{}", "[]"}) {
                String args = "{\"path\":\"lines.txt\",\"" + field + "\":" + value + "}";
                assertThrows(IllegalArgumentException.class, () -> tools.find("read_file").orElseThrow().execute(args), args);
            }
        }
        assertEquals("", execute(tools, "read_file", Map.of("path", "lines.txt", "start_line", Integer.MAX_VALUE)));
    }

    @Test void boundedTextReadsKeepLineSemanticsAndRejectInvalidUtf8() throws Exception {
        ToolRegistry tools = tools();
        Path file = temporary.resolve("workspace/lines.txt");
        Files.writeString(file, "one\r\ntwo\rthree\n\n");
        assertEquals("1: one\n2: two\n3: three\n4: \n", execute(tools, "read_file", Map.of("path", "lines.txt")));
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> execute(tools, "read_file", Map.of("path", "lines.txt")));
        assertEquals("", execute(tools, "search_files", Map.of("query", "(")));
        Files.writeString(file, "x".repeat(1_000_001));
        assertThrows(IllegalArgumentException.class, () -> execute(tools, "read_file", Map.of("path", "lines.txt")));
        assertEquals("", execute(tools, "search_files", Map.of("query", "x")));
    }

    @Test void drainsLargeProcessOutputWhileRunning() throws Exception {
        ToolRegistry tools = WorkspaceTools.create(temporary.resolve("workspace"), ignored -> true, Duration.ofSeconds(10));
        String result = execute(tools, "run_command", Map.of("command", fixtureCommand("output")));
        assertTrue(result.startsWith("退出码：0"), result);
        assertTrue(result.endsWith("[输出已截断]"));
        assertTrue(result.length() < 11_100);
    }

    @Test void interruptionDuringApprovalPreventsCommandLaunch() throws Exception {
        ToolRegistry tools = WorkspaceTools.create(temporary.resolve("workspace"), ignored -> {
            Thread.currentThread().interrupt();
            return true;
        });
        try {
            assertThrows(InterruptedException.class,
                    () -> execute(tools, "run_command", Map.of("command", "echo launched > launched.txt")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(temporary.resolve("workspace/launched.txt")));
        } finally { Thread.interrupted(); }
    }

    @Test void timeoutTerminatesChildProcess() throws Exception {
        ToolRegistry tools = WorkspaceTools.create(temporary.resolve("workspace"), ignored -> true, Duration.ofSeconds(2));
        String result = execute(tools, "run_command", Map.of("command", fixtureCommand("tree")));
        assertTrue(result.startsWith("命令超时"), result);
        String marker = "child-pid=";
        int index = result.indexOf(marker);
        assertTrue(index >= 0, result);
        long pid = Long.parseLong(result.substring(index + marker.length()).lines().findFirst().orElseThrow().trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "超时后子进程仍存活：" + pid);
    }

    private static String fixtureCommand(String action) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        String classes = Path.of(CommandFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String fixture = CommandFixture.class.getName();
        String quotedFixture = System.getProperty("os.name").toLowerCase().contains("win")
                ? "\"" + fixture + "\"" : "'" + fixture + "'";
        return "\"" + java + "\" -cp \"" + classes + "\" " + quotedFixture + " " + action;
    }

    private static boolean windows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"); }

    public static final class CommandFixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.write("x".repeat(512_000).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (args[0].equals("tree")) {
                boolean onWindows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
                // 使用本机等待进程，避免二次冷启动 JVM 耗尽两秒的被测命令截止时间。
                // Windows ping 仅访问回环地址；两个分支都会真实创建并等待子进程。
                ProcessBuilder childCommand = onWindows
                        ? new ProcessBuilder("cmd.exe", "/d", "/c", "ping -n 30 127.0.0.1 >nul")
                        : new ProcessBuilder("/bin/sh", "-c", "sleep 30");
                Process child = childCommand.inheritIO().start();
                System.out.println("child-pid=" + child.pid());
                System.out.flush();
                child.waitFor();
                return;
            }
            Thread.sleep(30_000);
        }
    }
}
