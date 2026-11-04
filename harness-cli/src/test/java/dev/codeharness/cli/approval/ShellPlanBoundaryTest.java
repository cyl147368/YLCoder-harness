package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.cli.tools.WorkspaceTools;
import dev.codeharness.core.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellPlanBoundaryTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void 长度上限统一且不再把不支持的语法误报为超过128字符() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("length")).toRealPath();
        var gate = new ReadOnlyCommandGate();
        var unsupported = gate.check(new CommandApproval.Request(workspace, "unavailable " + "value ".repeat(35)));
        assertTrue(unsupported.plan().isEmpty());
        assertFalse(unsupported.reason().contains("128"), unsupported.reason());
        assertFalse(unsupported.reason().contains("超过"), unsupported.reason());
        var tooLong = gate.check(new CommandApproval.Request(workspace, "echo " + "x".repeat(4096)));
        assertTrue(tooLong.plan().isEmpty());
        assertTrue(tooLong.reason().contains("4096"));
    }

    @Test void 文件统计保留中文空格参数并验证实际字节数() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("files")).toRealPath();
        Files.writeString(workspace.resolve("中文 readme.md"), "abc\n", StandardCharsets.UTF_8);
        var reviewer = localOnly();
        String output = execute(workspace, reviewer, "wc -c '中文 readme.md'; echo '---'; ls -ld .");
        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.matches("(?s).*\\b4\\s+中文 readme.md.*"), output);
        assertTrue(output.contains("---"), output);
        assertEquals(0, reviewer.stats().requests());
        assertEquals(1, reviewer.stats().localApprovals());
    }

    @Test void 文件统计参数注入敏感路径和设备不能进入分类器() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("veto")).toRealPath();
        Files.writeString(workspace.resolve("README.md"), "fixture");
        Files.createDirectory(workspace.resolve("directory"));
        var reviewer = localOnly();
        for (String command : List.of("wc -c .env; echo done", "wc -l .ssh/id_rsa; echo done",
                "wc -c --files0-from=README.md; echo done", "wc -c ../outside; echo done",
                "wc -c NUL; echo done", "wc -c CON.txt; echo done", "wc -c *.md; echo done",
                "wc -c directory; echo done", "wc -c README.md:stream; echo done",
                "ls -L directory; echo done", "ls ＆ whoami")) {
            assertFalse(reviewer.review(new CommandApproval.Request(workspace, command)).approved(), command);
        }
        assertEquals(0, reviewer.stats().requests());
    }

    @Test void 分类器执行重建的Git复合命令且不启动工作区同名包装器() throws Exception {
        Path workspace = repository("classifier");
        Files.writeString(workspace.resolve("git.cmd"), "@echo shadow>shadow-marker");
        Files.writeString(workspace.resolve("git.exe"), "not an executable");
        var calls = new AtomicInteger();
        var reviewer = new CommandReview(request -> {
            calls.incrementAndGet();
            assertTrue(request.tools().isEmpty());
            assertTrue(request.messages().getLast().content().contains("--no-ext-diff"));
            return ChatResponse.text("{\"decision\":\"approve\"}");
        }, Duration.ofSeconds(2), 8, ignored -> {});
        String output = execute(workspace, reviewer,
                "git show --stat HEAD; echo '---'; git show --oneline -1 | head -10");
        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.contains("fixture"), output);
        assertTrue(output.contains("---"), output);
        assertFalse(Files.exists(workspace.resolve("shadow-marker")));
        assertEquals(1, calls.get());
        assertEquals(1, reviewer.stats().requests());
        assertEquals(0, reviewer.stats().localApprovals());
    }

    @Test void 当前目录和目录操作数可进入无工具分类器并执行真实查询() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("find-query")).toRealPath();
        Path directory = Files.createDirectory(workspace.resolve("src"));
        Files.writeString(workspace.resolve("README.md"), "root fixture");
        Files.writeString(directory.resolve("README.md"), "nested fixture");
        var reviewer = new CommandReview(request -> {
            assertTrue(request.tools().isEmpty());
            return ChatResponse.text("{\"decision\":\"approve\"}");
        }, Duration.ofSeconds(2), 8, ignored -> {});
        for (String query : List.of("find . -maxdepth 1 -name README.md", "find src -maxdepth 1 -name README.md")) {
            String output = execute(workspace, reviewer, query);
            assertTrue(output.startsWith("退出码：0"), output);
            assertTrue(output.contains("README.md"), output);
        }
        assertEquals(2, reviewer.stats().requests());
    }

    @Test void 目录查询的链接跟随执行动作和敏感目录仍不得交给分类器() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("find-veto")).toRealPath();
        var reviewer = localOnly();
        for (String query : List.of("find -L . -type f", "find -H . -type f", "find . -follow -type f",
                "find . -delete", "find . -exec whoami ;", "find ../outside -type f",
                "find .ssh -maxdepth 1 -type f")) {
            assertFalse(reviewer.review(new CommandApproval.Request(workspace, query)).approved(), query);
        }
        assertEquals(0, reviewer.stats().requests());
    }

    @Test void AI审核期间文件变化会作废整条执行计划() throws Exception {
        Path workspace = repository("changed");
        var reviewer = new CommandReview(request -> {
            assertDoesNotThrow(() -> Files.writeString(workspace.resolve("README.md"), "changed during review"));
            return ChatResponse.text("{\"decision\":\"approve\"}");
        }, Duration.ofSeconds(2), 8, ignored -> {});
        var result = reviewer.review(new CommandApproval.Request(workspace,
                "wc -c README.md; git show --stat HEAD"));
        assertFalse(result.approved());
        assertEquals(CommandReview.Status.CONDITIONS_CHANGED, result.status());
        assertEquals(1, reviewer.stats().requests());
    }

    @Test void 目录链接不能通过文件统计或目录查询读取工作区外部() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("links")).toRealPath();
        Path external = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(external.resolve("secret.txt"), "outside");
        Path link = workspace.resolve("linked");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), external.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assumeTrue(process.exitValue() == 0, "本机不能创建测试 junction");
        } else Files.createSymbolicLink(link, external);
        try {
            var reviewer = localOnly();
            for (String command : List.of("wc -c linked/secret.txt; echo done", "ls -la linked; echo done",
                    "ls -1d */; echo done"))
                assertFalse(reviewer.review(new CommandApproval.Request(workspace, command)).approved(), command);
            assertEquals(0, reviewer.stats().requests());
        } finally { Files.deleteIfExists(link); }
    }

    private Path repository(String name) throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve(name)).toRealPath();
        Path git = ReadOnlyCommandGate.installedGit(workspace);
        assumeTrue(git != null, "没有外部 Git");
        runGit(git, workspace, "init", "--quiet");
        Files.writeString(workspace.resolve("README.md"), "fixture\n");
        runGit(git, workspace, "add", "README.md");
        runGit(git, workspace, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--quiet", "-m", "fixture");
        return workspace;
    }

    private static void runGit(Path git, Path workspace, String... args) throws Exception {
        var argv = new ArrayList<String>(List.of(git.toString()));
        argv.addAll(List.of(args));
        var builder = new ProcessBuilder(argv).directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    private static CommandReview localOnly() {
        return new CommandReview(request -> { throw new AssertionError("不应调用 AI"); },
                Duration.ofSeconds(2), 8, ignored -> {});
    }

    private static String execute(Path workspace, CommandReview reviewer, String command) throws Exception {
        var tools = WorkspaceTools.createWithCommandApproval(workspace, request -> {
            var outcome = reviewer.review(request);
            assertTrue(outcome.approved(), outcome.reason());
            return outcome.decision();
        });
        return tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", command)));
    }
}
