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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitDiffReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void 指定多个文件的差异免确认且不执行外部程序或修改索引() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repo"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        List<String> names = List.of("build.sh", "pom.xml", "soulclaw-knowledge-server/src/main/resources/application.yml", "中文 文件.txt");
        for (String name : names) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "before\n");
        }
        command(git, root, "add", ".");
        command(git, root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture");
        for (String name : names) Files.writeString(root.resolve(name), "after\n");
        command(git, root, "config", "diff.external", "echo bad > external-marker");
        command(git, root, "config", "diff.custom.command", "echo bad > driver-marker");
        command(git, root, "config", "diff.custom.textconv", "echo bad > textconv-marker");
        command(git, root, "config", "core.fsmonitor", "echo bad > fsmonitor-marker");
        command(git, root, "config", "core.pager", "echo bad > pager-marker");
        Files.writeString(root.resolve(".gitattributes"), "*.xml diff=custom\n");
        byte[] index = Files.readAllBytes(root.resolve(".git/index"));
        var timestamp = Files.getLastModifiedTime(root.resolve(".git/index"));
        var reviewer = reviewer();
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            var outcome = reviewer.review(request);
            assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status(), outcome.reason());
            assertTrue(outcome.arguments().containsAll(List.of("--no-ext-diff", "--no-textconv", "--ignore-submodules=all", "--literal-pathspecs")));
            return outcome.decision();
        });
        for (String text : List.of("git diff -- build.sh pom.xml soulclaw-knowledge-server/src/main/resources/application.yml",
                "git diff", "git diff --stat", "git diff --numstat", "git diff --shortstat", "git diff --name-only",
                "git diff --name-status", "git diff --no-ext-diff --no-textconv --no-color -- pom.xml",
                "git diff -- '中文 文件.txt'", "git diff -- \"中文 文件.txt\"")) {
            String output = tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", text)));
            assertTrue(output.startsWith("退出码：0"), output);
            assertTrue(output.contains("after") || output.contains("pom.xml") || output.contains("changed"), output);
        }
        assertEquals(0, reviewer.stats().requests());
        assertEquals(10, reviewer.stats().localApprovals());
        for (String marker : List.of("external", "driver", "textconv", "fsmonitor", "pager"))
            assertFalse(Files.exists(root.resolve(marker + "-marker")), marker);
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index")));
        assertEquals(timestamp, Files.getLastModifiedTime(root.resolve(".git/index")));
    }

    @Test void 暂存和删除文件可查看且长路径不再触发旧长度限制() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("staged"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        String name = "module/" + "long-module-name/".repeat(5) + "application-config.yml";
        Files.createDirectories(root.resolve(name).getParent());
        Files.writeString(root.resolve(name), "staged-content\n");
        command(git, root, "add", ".");
        var reviewer = reviewer();
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> reviewer.review(request).decision());
        for (String option : List.of("--cached", "--staged")) {
            String text = "git diff " + option + " -- " + name;
            assertTrue(text.length() > 128);
            String output = tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", text)));
            assertTrue(output.contains("+staged-content"), output);
        }
        Files.delete(root.resolve(name));
        String output = tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", "git diff -- " + name)));
        assertTrue(output.contains("-staged-content"), output);
        command(git, root, "config", "filter.bad.clean", "echo bad > marker");
        var blocked = reviewer.review(new CommandApproval.Request(root.toRealPath(), "git diff -- " + name));
        assertEquals(CommandReview.Status.SAFETY_CHECK, blocked.status());
        assertTrue(blocked.reason().contains("过滤器"));
        assertFalse(Files.exists(root.resolve("marker")));
        assertEquals(0, reviewer.stats().requests());
    }

    @Test void 有界只读复合摘要走本地快速路径且不执行未列出的程序() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("combined"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        Files.writeString(root.resolve("pom.xml"), "before\n");
        command(git, root, "add", "pom.xml");
        command(git, root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--quiet", "-m", "fixture");
        Files.writeString(root.resolve("pom.xml"), "after\n");
        var review = new CommandReview(request -> { throw new AssertionError("固定只读链不应请求模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> review.review(request).decision());
        String command = "git log --oneline -5 && echo --- && git diff --stat | tail -20 && echo --- && git status --short | wc -l";

        String output = tools.find("run_command").orElseThrow()
                .execute(JSON.writeValueAsString(Map.of("command", command)));

        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.contains("fixture"), output);
        assertTrue(output.contains("pom.xml"), output);
        assertTrue(output.contains("---"), output);
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().localApprovals());
    }

    @Test void 目录摘要分号管线和stderr空设备重定向均走本地快速路径() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("directory-summary"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        Files.createDirectory(root.resolve("server"));
        Files.createDirectory(root.resolve("client"));
        Files.writeString(root.resolve("sample.txt"), "before\n");
        command(git, root, "add", "sample.txt");
        command(git, root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--quiet", "-m", "fixture");
        Files.writeString(root.resolve("sample.txt"), "after\n");
        var review = new CommandReview(request -> { throw new AssertionError("固定只读链不应请求模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> review.review(request).decision());
        String text = "ls -1d */ 2>/dev/null; echo ---; git status --short | head -30; echo ---; git log --oneline -5";

        String output = tools.find("run_command").orElseThrow()
                .execute(JSON.writeValueAsString(Map.of("command", text)));

        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.contains("server") && output.contains("client"), output);
        assertTrue(output.contains("sample.txt") && output.contains("fixture"), output);
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().localApprovals());
    }

    @Test void 长只读链支持多目录ls多文件wc和stderr合并管道() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("long-mixed"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        for (String directory : List.of("soulclaw-knowledge-server", "soulclaw-knowledge-client",
                "soulclaw-knowledge-infra")) Files.createDirectory(root.resolve(directory));
        Files.writeString(root.resolve("README.md"), "readme");
        Files.writeString(root.resolve("CLAUDE.md"), "claude");
        Files.writeString(root.resolve("MySQL.md"), "mysql");
        var review = new CommandReview(request -> { throw new AssertionError("固定只读链不应请求模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> review.review(request).decision());
        String text = "ls -la; echo '---'; wc -c README.md CLAUDE.md MySQL.md; echo '---';"
                + " ls soulclaw-knowledge-server soulclaw-knowledge-client soulclaw-knowledge-infra 2>&1 | head -50";
        assertTrue(text.length() > 128);

        String output = tools.find("run_command").orElseThrow()
                .execute(JSON.writeValueAsString(Map.of("command", text)));

        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.contains("README.md") && output.contains("CLAUDE.md") && output.contains("MySQL.md"), output);
        assertTrue(output.contains("soulclaw-knowledge-server") && output.contains("soulclaw-knowledge-infra"), output);
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().localApprovals());
    }

    @Test void 路径魔法外部路径输出参数和命令拼接不会进入模型() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("unsafe"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        var reviewer = reviewer();
        for (String text : List.of("git diff --output=result", "git diff --ext-diff", "git diff --textconv",
                "git diff --no-index . ../other", "git diff -- ../other", "git diff -- /etc/passwd",
                "git diff -- C:/secret", "git diff -- .git/config", "git diff -- src/../../other", "git diff -- ':(top)secret'",
                "git diff -- '*.xml'", "git diff -- pom.xml > result",
                "git diff -- $(whoami)", "git diff -- `whoami`", "git diff -- \"$HOME\"", "git diff -- %TEMP%",
                "git diff -- \"unterminated", "git diff -- .GIT/config",
                "git diff -- .harness/sessions", "git diff -- src./file", "git diff -- pom.xml\n")) {
            assertFalse(reviewer.review(new CommandApproval.Request(root.toRealPath(), text)).approved(), text);
        }
        assertEquals(0, reviewer.stats().requests());
        assertFalse(Files.exists(root.resolve("result")));
    }

    @Test void 默认差异不遍历无关链接但显式链接路径仍不放行() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("links"));
        Path git = git(root);
        command(git, root, "init", "--quiet");
        Path external = Files.createDirectory(temporary.resolve("external"));
        Path link = root.resolve("linked");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), external.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assumeTrue(process.exitValue() == 0, "无法创建验收 junction");
        } else Files.createSymbolicLink(link, external);
        try {
            var reviewer = reviewer();
            var defaultDiff = reviewer.review(new CommandApproval.Request(root.toRealPath(), "git diff --stat"));
            assertTrue(defaultDiff.approved(), defaultDiff.reason());
            assertEquals(CommandReview.Status.LOCAL_APPROVED, defaultDiff.status());
            for (String text : List.of("git diff -- linked", "git diff -- linked/missing.txt")) {
                var result = reviewer.review(new CommandApproval.Request(root.toRealPath(), text));
                assertFalse(result.approved(), text);
                assertEquals(CommandReview.Status.SAFETY_CHECK, result.status());
            }
            assertEquals(0, reviewer.stats().requests());
        } finally {
            // 只解除此测试创建的链接，避免 JUnit 清理器把 junction 当普通目录遍历。
            Files.deleteIfExists(link);
        }
    }

    private static CommandReview reviewer() {
        return new CommandReview(request -> { fail("只读快速路径和拒绝路径均不应调用审核 AI"); return ChatResponse.text(""); },
                Duration.ofSeconds(1), 8, ignored -> {});
    }

    private static Path git(Path root) {
        Path git = ReadOnlyCommandGate.installedGit(root);
        assumeTrue(git != null, "本机没有工作区以外的 Git");
        return git;
    }

    private static void command(Path git, Path root, String... arguments) throws Exception {
        var argv = new ArrayList<>(List.of(git.toString()));
        argv.addAll(List.of(arguments));
        var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", windows ? "NUL" : "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", windows ? "NUL" : "/dev/null");
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }
}
