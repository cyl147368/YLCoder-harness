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

class NativeSearchExecutionTest {
    @org.junit.jupiter.api.Test void 不把不存在路径的祖先文件当作搜索目标() throws Exception {
        Path root = java.nio.file.Files.createDirectories(temporary.resolve("ancestor-workspace")).toRealPath();
        java.nio.file.Files.createDirectories(root.resolve("src"));
        java.nio.file.Files.writeString(root.resolve("src/A.java"), "outside-selected-root");
        var plan = NativeReadOnlyQuery.plan(new CommandApproval.Request(root, "rg -n outside-selected-root src/A.java/child"));
        String result = NativeReadOnlyQuery.execute(plan);
        assertFalse(result.contains("outside-selected-root"), result);
        assertTrue(result.contains("未找到路径"), result);
    }
    @TempDir Path temporary;

    private Path workspace() throws IOException { return Files.createDirectories(temporary.resolve("workspace")).toRealPath(); }

    private NativeReadOnlyQuery.Plan plan(Path workspace, String command) throws IOException {
        return NativeReadOnlyQuery.plan(new CommandApproval.Request(workspace, command));
    }

    private String run(Path root, String command) throws Exception { return NativeReadOnlyQuery.execute(plan(root, command)); }

    @Test void 正文查询仅匹配指定多目录并正确返回行号() throws Exception {
        Path root = workspace();
        write(root, "src/Main.java", "ordinary\r\nTODO implement\r\nordinary\r\nfixme later");
        write(root, "docs/guide.md", "TODO documentation\n");
        write(root, "other/hidden-from-selection.txt", "TODO outside selected directories\n");
        write(root, "root.txt", "TODO outside selected directories\n");
        String output = run(root, "rg -ni 'TODO|FIXME' src docs");
        assertTrue(output.contains("src/Main.java:2: TODO implement\n"), output);
        assertTrue(output.contains("src/Main.java:4: fixme later\n"), output);
        assertTrue(output.contains("docs/guide.md:1: TODO documentation\n"), output);
        assertFalse(output.contains("outside selected directories"), output);
        assertFalse(output.contains("ordinary"), output);
        assertTrue(output.contains("原生正文查询"), output);
        String noNumbers = run(root, "rg -F TODO src/Main.java");
        assertTrue(noNumbers.contains("src/Main.java: TODO implement\n"), noNumbers);
        assertFalse(noNumbers.contains("src/Main.java:2:"), noNumbers);
    }

    @Test void 多目录文件名搜索与显式文件只输出选中匹配名称() throws Exception {
        Path root = workspace();
        for (String name : List.of("src/A.java", "src/nested/B.java", "docs/readme.md", "other/C.java", "root.java"))
            write(root, name, "BODY_MUST_NOT_BE_READ");
        String output = run(root, "rg --files src docs -g '*.java' -g '*.md'");
        for (String included : List.of("src/A.java", "src/nested/B.java", "docs/readme.md"))
            assertTrue(output.contains("\n" + included + "\n"), output);
        assertFalse(output.contains("other/C.java"), output);
        assertFalse(output.contains("root.java"), output);
        assertFalse(output.contains("BODY_MUST_NOT_BE_READ"), output);
        String explicit = run(root, "rg --files src/A.java");
        assertTrue(explicit.contains("\nsrc/A.java\n"), explicit);
        assertFalse(explicit.contains("src/nested/B.java"), explicit);
    }

    @Test void rg文件列表可裁剪路径前缀后再限制输出行数() throws Exception {
        Path root = workspace();
        String prefix = "soulclaw-knowledge-server/src/main/java/org/baai/data/soulclaw/knowledge/server/";
        write(root, prefix + "api/Controller.java", "content-not-read");
        write(root, prefix + "service/Service.java", "content-not-read");
        write(root, prefix + "model/Record.java", "content-not-read");
        String output = run(root, "rg --files soulclaw-knowledge-server/src/main/java"
                + " | sed 's#" + prefix + "##' | head -3");

        assertTrue(output.contains("api/Controller.java"), output);
        assertTrue(output.contains("service/Service.java"), output);
        assertFalse(output.contains(prefix + "api/Controller.java"), output);
        assertFalse(output.contains("content-not-read"), output);
    }

    @Test void 默认不读隐藏内容且hidden无法扩大敏感路径和内部目录权限() throws Exception {
        Path root = workspace();
        write(root, "src/Main.java", "FINDME visible\n");
        write(root, ".hidden/readme.md", "FINDME HIDDEN_CONTENT\n");
        for (String name : List.of(".env", ".env.local", "config/private.key", "config/cert.pem", "secrets/passwords.txt",
                "credentials.json", "config.local.yaml", ".ssh/config", ".aws/config", ".git/objects/private",
                ".harness/sessions/private.json", "nested/.git/config", "nested/.harness/private.txt"))
            write(root, name, "FINDME SENSITIVE_CONTENT_MUST_NOT_BE_READ\n");
        String visible = run(root, "rg -n FINDME .");
        assertTrue(visible.contains("src/Main.java:1: FINDME visible"), visible);
        assertFalse(visible.contains("HIDDEN_CONTENT"), visible);
        assertFalse(visible.contains("SENSITIVE_CONTENT_MUST_NOT_BE_READ"), visible);
        String hidden = run(root, "rg -n --hidden FINDME .");
        assertTrue(hidden.contains(".hidden/readme.md:1: FINDME HIDDEN_CONTENT"), hidden);
        assertFalse(hidden.contains("SENSITIVE_CONTENT_MUST_NOT_BE_READ"), hidden);
        String explicit = run(root, "rg -n --hidden FINDME .env config/private.key .git .harness");
        assertFalse(explicit.contains("SENSITIVE_CONTENT_MUST_NOT_BE_READ"), explicit);
        assertFalse(explicit.contains("HIDDEN_CONTENT"), explicit);
    }

    @Test void 二进制无效UTF8与过大文件不会输出部分正文() throws Exception {
        Path root = workspace();
        write(root, "text.txt", "FINDME valid\n");
        Files.write(root.resolve("binary.dat"), "FINDME BINARY_CONTENT\0\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("invalid.dat"), new byte[]{'F', 'I', 'N', 'D', 'M', 'E', ' ', (byte) 0xc3, (byte) 0x28});
        write(root, "oversized.txt", "FINDME OVERSIZED_CONTENT\n" + "x".repeat(1_000_000));
        String output = run(root, "rg -n FINDME .");
        assertTrue(output.contains("text.txt:1: FINDME valid"), output);
        assertFalse(output.contains("BINARY_CONTENT"), output);
        assertFalse(output.contains("OVERSIZED_CONTENT"), output);
        assertFalse(output.contains("invalid.dat:"), output);
        assertTrue(output.contains("跳过 3 个"), output);
    }

    @Test void 正则引号内管道与head仅限制结果不执行外部程序() throws Exception {
        Path root = workspace();
        write(root, "src/log.txt", "error first\nnormal\nfatal second\nerror third\nfatal fourth\n");
        write(root, "rg.cmd", "@echo unexpected>unexpected-rg.txt");
        write(root, "head.cmd", "@echo unexpected>unexpected-head.txt");
        String output = run(root, "rg -n 'error|fatal$|fatal second' src | head -n 2");
        assertTrue(output.contains("src/log.txt:1: error first\n"), output);
        assertTrue(output.contains("src/log.txt:3: fatal second\n"), output);
        assertFalse(output.contains("error third"), output);
        assertFalse(output.contains("fatal fourth"), output);
        assertTrue(output.contains("结果已截断"), output);
        assertFalse(Files.exists(root.resolve("unexpected-rg.txt")));
        assertFalse(Files.exists(root.resolve("unexpected-head.txt")));
    }

    @Test void 无匹配与缺失根路径返回可处理提示且不请求人工审批() throws Exception {
        Path root = workspace();
        write(root, "src/existing.txt", "ordinary\n");
        String output = run(root, "rg -n MISSING src");
        assertTrue(output.contains("未找到匹配"), output);
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            try { return CommandApproval.Decision.nativeQuery(NativeReadOnlyQuery.plan(request)); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        String input = new ObjectMapper().writeValueAsString(Map.of("command", "rg --files missing-root"));
        String missing = tools.find("run_command").orElseThrow().execute(input);
        assertTrue(missing.startsWith("退出码：0\n"), missing);
        assertTrue(missing.contains("未找到路径") && missing.contains("missing-root"), missing);
        assertFalse(missing.contains("未获批准"), missing);
    }

    @Test void 输出遍历深度与时间上限保持有效() throws Exception {
        Path root = workspace();
        for (int index = 0; index < 6; index++) write(root, "src/nested/" + index + ".txt", "FINDME " + "x".repeat(120) + "\n");
        var query = plan(root, "rg -n FINDME src");
        for (var limits : List.of(
                new NativeReadOnlyQuery.Limits(1, 100, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 1, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 200, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 1, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 10, Duration.ofNanos(1)))) {
            String output = NativeReadOnlyQuery.execute(query, limits);
            assertTrue(output.contains("结果已截断"), output);
            assertFalse(output.contains("未找到匹配"), output);
            assertTrue(output.length() <= limits.outputChars(), output);
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> NativeReadOnlyQuery.execute(query));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void 总读取字节上限阻止继续扫描后续文件() throws Exception {
        Path root = workspace();
        String content = "x".repeat(899_999) + "\n";
        for (int index = 0; index < 9; index++) write(root, "src/" + index + ".txt", content);
        write(root, "src/9.txt", "FINDME MUST_NOT_REACH_AFTER_BYTE_LIMIT\n");
        String output = NativeReadOnlyQuery.execute(plan(root, "rg FINDME src"),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 10, Duration.ofSeconds(10)));
        assertTrue(output.contains("结果已截断"), output);
        assertFalse(output.contains("MUST_NOT_REACH_AFTER_BYTE_LIMIT"), output);
        assertFalse(output.contains("未找到匹配"), output);
    }

    @Test void 输出中的控制字符被转义且不能操纵终端() throws Exception {
        Path root = workspace();
        write(root, "src/log.txt", "FINDME \u001b[31m warning\titem\n");
        String output = run(root, "rg -n FINDME src");
        assertTrue(output.contains("\\u001b[31m"), output);
        assertTrue(output.contains("\\u0009"), output);
        assertFalse(output.contains("\u001b"), output);
    }

    @Test void 符号链接目录及文件不会进入正文查询() throws Exception {
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        write(outside, "private.txt", "FINDME OUTSIDE_CONTENT\n");
        Path directoryLink = root.resolve("directory-link"), fileLink = root.resolve("file-link.txt");
        try {
            try {
                Files.createSymbolicLink(directoryLink, outside);
                Files.createSymbolicLink(fileLink, outside.resolve("private.txt"));
            } catch (IOException | UnsupportedOperationException | SecurityException error) {
                assumeTrue(false, "当前平台不允许创建符号链接：" + error.getMessage());
            }
            String output = run(root, "rg --hidden -n FINDME directory-link file-link.txt");
            assertFalse(output.contains("OUTSIDE_CONTENT"), output);
            assertThrows(IOException.class, () -> plan(directoryLink, "rg -n FINDME ."));
        } finally {
            Files.deleteIfExists(fileLink);
            Files.deleteIfExists(directoryLink);
        }
    }

    @Test void Windows联接与审批后工作区替换不会进入正文查询() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "Windows junction 测试");
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        write(outside, "private.txt", "FINDME OUTSIDE_CONTENT\n");
        Path junction = root.resolve("junction");
        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        String diagnostic = new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), diagnostic);
        try {
            String output = run(root, "rg --hidden -n FINDME junction");
            assertFalse(output.contains("OUTSIDE_CONTENT"), output);
            assertThrows(IOException.class, () -> plan(junction, "rg -n FINDME ."));
        } finally { Files.deleteIfExists(junction); }
        var query = plan(root, "rg -n FINDME .");
        Files.move(root, temporary.resolve("previous"));
        Files.createDirectory(root);
        write(root, "new.txt", "FINDME UNAPPROVED_REPLACEMENT\n");
        assertThrows(IOException.class, () -> NativeReadOnlyQuery.execute(query));
    }

    private static void write(Path root, String name, String text) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
