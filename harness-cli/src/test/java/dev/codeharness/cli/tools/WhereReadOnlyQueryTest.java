package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WhereReadOnlyQueryTest {
    @TempDir Path temporary;

    private Path workspace() throws IOException {
        return Files.createDirectories(temporary.resolve("workspace")).toRealPath();
    }

    private NativeReadOnlyQuery.Plan plan(Path workspace, String command) throws IOException {
        return NativeReadOnlyQuery.plan(new CommandApproval.Request(workspace, command));
    }

    private String run(Path root, String command) throws Exception {
        return NativeReadOnlyQuery.execute(plan(root, command));
    }

    @Test void 接受当前工作区的大小写变体和有限文件名筛选() {
        for (String command : List.of("where /r . AGENTS.md", "WHERE /R . agents.md",
                "where.exe /r \".\" \"AGENTS.md\"", "WhErE.ExE /R './' 'README.md'",
                "where /r .\\ pom.xml", "where /r \".\\\" AGENTS.md",
                "where /r . AGENTS.md README.md '*.java' '*Test*' 'application*.yml'",
                "where /r . '*security*' '*CONTROLLER.JAVA'"))
            assertTrue(NativeReadOnlyQuery.supports(command), command);
    }

    @Test void 拒绝外部路径未知开关注入和非文件名模式() {
        for (String command : List.of("where AGENTS.md", "where /r .", "where /r .. AGENTS.md",
                "where /r ../ AGENTS.md", "where /r .\\.. AGENTS.md", "where /r C:\\ AGENTS.md",
                "where /r \\server\\share AGENTS.md", "where /r %TEMP% AGENTS.md",
                "where /r .\\src AGENTS.md", "C:\\Windows\\System32\\where.exe /r . AGENTS.md",
                "where /r . .github/**", "where /r . '../AGENTS.md'", "where /r . '*.env'",
                "where /r . '!target'", "where /r . '*.*'", "where /r . '$PATH:AGENTS.md'",
                "where /r . AGENTS.md /q", "where /f /r . AGENTS.md", "where /r . AGENTS.md /t",
                "where /r . AGENTS.md > out", "where /r . AGENTS.md && echo x",
                "where /r . AGENTS.md|more", "where /r . AGENTS.md & echo x",
                "where /r . AGENTS.md;echo x", "where /r . $(whoami)", "where /r . `whoami`",
                "where /r . AGENTS.md\n", "where /r . AGENTS.md\r", "where\t/r . AGENTS.md",
                "where /r . 'AGENTS.md\"", "where /r . AGENTS.md ", " where /r . AGENTS.md"))
            assertFalse(NativeReadOnlyQuery.supports(command), command);
    }

    @Test void 多筛选与总长度复用已有上限() {
        String repeated = "where /r ." + " AGENTS.md".repeat(64);
        assertTrue(NativeReadOnlyQuery.supports(repeated));
        assertFalse(NativeReadOnlyQuery.supports(repeated + " pom.xml"));
        String command = "where /r . AGENTS.md";
        String atLimit = "where" + " ".repeat(4096 - command.length() + 1) + "/r . AGENTS.md";
        assertEquals(4096, atLimit.length());
        assertTrue(NativeReadOnlyQuery.supports(atLimit));
        assertFalse(NativeReadOnlyQuery.supports(atLimit.replace("where ", "where  ")));
    }

    @Test void 原生命令返回绝对路径忽略大小写不读取内容也不运行程序() throws Exception {
        Path root = workspace();
        List<String> included = List.of("AGENTS.md", "src/agents.md", "src/ReadMe.MD", "src/Main.JAVA",
                ".hidden/AgEnTs.Md");
        for (String name : included) write(root, name, "不能读取的文件内容");
        for (String name : List.of(".git/AGENTS.md", ".harness/AGENTS.md", ".GIT/README.md",
                "nested/.harness/AGENTS.md", "src/unmatched.txt", "AGENTS.md.exe"))
            write(root, name, "不能读取的文件内容");
        // 如果实现错误地转交 shell，这个工作区内的同名命令会留下标记。
        Files.writeString(root.resolve("where.cmd"), "@echo unexpected>unexpected.txt");
        String result = run(root, "WHERE.EXE /R \".\" AGENTS.md 'README.md' '*.java'");
        assertTrue(result.contains("不展开 PATH/PATHEXT"), result);
        for (String name : included)
            assertTrue(result.contains("\n" + root.resolve(name) + "\n"), name + "\n" + result);
        assertFalse(result.contains("不能读取的文件内容"), result);
        assertFalse(result.contains(root.resolve(".git").toString()), result);
        assertFalse(result.contains(root.resolve(".harness").toString()), result);
        assertFalse(result.contains(root.resolve("nested/.harness").toString()), result);
        assertFalse(result.contains("unmatched.txt"), result);
        assertFalse(result.contains("AGENTS.md.exe"), result);
        assertFalse(Files.exists(root.resolve("unexpected.txt")));
    }

    @Test void 没有匹配是成功查询并明确说明而不是工具失败() throws Exception {
        Path root = workspace();
        var tools = WorkspaceTools.createWithCommandApproval(root,
                request -> {
                    try { return CommandApproval.Decision.nativeQuery(NativeReadOnlyQuery.plan(request)); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                });
        String input = new ObjectMapper().writeValueAsString(Map.of("command", "where /r . AGENTS.md"));
        String output = tools.find("run_command").orElseThrow().execute(input);
        assertTrue(output.startsWith("退出码：0\n"), output);
        assertTrue(output.endsWith("[未找到匹配文件]\n"), output);
    }

    @Test void 遍历结果深度时间和取消限制保持有效() throws Exception {
        Path root = workspace();
        for (int index = 0; index < 8; index++) write(root, "src/" + index + "/AGENTS.md", "");
        var query = plan(root, "where /r . AGENTS.md");
        for (var limits : List.of(
                new NativeReadOnlyQuery.Limits(1, 100, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 1, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 200, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 1, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 10, Duration.ofNanos(1)))) {
            String output = NativeReadOnlyQuery.execute(query, limits);
            assertTrue(output.contains("结果已截断"), output);
            assertFalse(output.contains("未找到匹配文件"), output);
            assertTrue(output.length() <= limits.outputChars(), output);
        }
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> NativeReadOnlyQuery.execute(query)); }
        finally { Thread.interrupted(); }
    }

    @Test void 拒绝审批后替换的工作区() throws Exception {
        Path root = workspace();
        var query = plan(root, "where /r . AGENTS.md");
        Files.move(root, temporary.resolve("previous"));
        Files.createDirectory(root);
        Files.writeString(root.resolve("AGENTS.md"), "新目录未经审批");
        assertThrows(IOException.class, () -> NativeReadOnlyQuery.execute(query));
    }

    @Test void 跳过符号链接文件和目录() throws Exception {
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("AGENTS.md"), "工作区外的正文");
        Path directoryLink = root.resolve("directory-link");
        Path fileLink = root.resolve("AGENTS.md");
        try {
            try {
                Files.createSymbolicLink(directoryLink, outside);
                Files.createSymbolicLink(fileLink, outside.resolve("AGENTS.md"));
            } catch (IOException | UnsupportedOperationException | SecurityException error) {
                assumeTrue(false, "当前平台不允许创建符号链接：" + error.getMessage());
            }
            String output = run(root, "where /r . AGENTS.md");
            assertTrue(output.endsWith("[未找到匹配文件]\n"), output);
            assertFalse(output.contains(outside.toString()), output);
            assertThrows(IOException.class, () -> plan(directoryLink, "where /r . AGENTS.md"));
        } finally {
            Files.deleteIfExists(fileLink);
            Files.deleteIfExists(directoryLink);
        }
    }

    @Test void 跳过Windows目录联接并拒绝联接工作区() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "Windows junction 测试");
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("AGENTS.md"), "工作区外的正文");
        Path junction = root.resolve("junction");
        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        String diagnostic = new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), diagnostic);
        try {
            String output = run(root, "where /r . AGENTS.md");
            assertTrue(output.endsWith("[未找到匹配文件]\n"), output);
            assertFalse(output.contains(outside.toString()), output);
            assertThrows(IOException.class, () -> plan(junction, "where /r . AGENTS.md"));
        } finally { Files.deleteIfExists(junction); }
    }

    private static void write(Path root, String name, String text) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
