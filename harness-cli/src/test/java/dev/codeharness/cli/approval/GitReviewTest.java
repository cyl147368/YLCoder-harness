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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void repositoriesWithFiltersStillRequireManualStatusAndDiffApproval() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repo"));
        Path git = availableGit(root);
        command(git, root, "init", "--quiet");
        Files.writeString(root.resolve("code.txt"), "before\n");
        command(git, root, "add", "code.txt");
        command(git, root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture");
        command(git, root, "config", "core.fsmonitor", "echo helper > helper-invoked.txt");
        // 反例先证明该项目配置确实会启动 helper；文件只写入隔离夹具。
        command(git, root, "status", "--short");
        assertTrue(Files.exists(root.resolve("helper-invoked.txt")), "反例未触发 fsmonitor helper");
        Files.delete(root.resolve("helper-invoked.txt"));
        command(git, root, "config", "diff.external", "echo helper > helper-invoked.txt");
        command(git, root, "config", "core.pager", "echo helper > helper-invoked.txt");
        Files.writeString(root.resolve(".gitattributes"), "code.txt filter=malicious\n");
        command(git, root, "config", "filter.malicious.clean", "echo clean-helper > filter-invoked.txt; cat");
        Files.writeString(root.resolve("code.txt"), "after with changes\n");
        command(git, root, "-c", "core.fsmonitor=false", "diff", "--stat", "--no-ext-diff", "--no-textconv", "--ignore-submodules=all");
        assertTrue(Files.exists(root.resolve("filter-invoked.txt")), "反例未触发 clean filter");
        Files.delete(root.resolve("filter-invoked.txt"));
        Path index = root.resolve(".git/index");
        byte[] oldIndex = Files.readAllBytes(index);
        var oldTime = Files.getLastModifiedTime(index);
        var reviewer = new CommandReview(request -> ChatResponse.text("{\"decision\":\"approve\"}"),
                Duration.ofSeconds(1), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            var outcome = reviewer.review(request);
            if (!outcome.approved()) return CommandApproval.Decision.denied();
            assertEquals(git, Path.of(outcome.arguments().getFirst()));
            assertTrue(outcome.arguments().contains("--no-optional-locks"));
            assertTrue(outcome.arguments().contains("core.fsmonitor=false"));
            return CommandApproval.Decision.direct(outcome.arguments());
        });
        for (String text : List.of("git branch --show-current")) {
            String result = tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(java.util.Map.of("command", text)));
            assertTrue(result.startsWith("退出码：0"), result);
        }
        for (String text : List.of("git status", "git status --short", "git status -s", "git status --porcelain",
                "git diff --stat", "git diff --name-only", "git diff --name-status", "git diff --cached --stat", "git diff --cached --name-only")) {
            assertThrows(SecurityException.class, () -> tools.find("run_command").orElseThrow().execute(
                    JSON.writeValueAsString(java.util.Map.of("command", text))), text);
        }
        assertFalse(Files.exists(root.resolve("helper-invoked.txt")));
        assertFalse(Files.exists(root.resolve("filter-invoked.txt")));
        assertArrayEquals(oldIndex, Files.readAllBytes(index));
        assertEquals(oldTime, Files.getLastModifiedTime(index));
        assertEquals(1, reviewer.stats().requests(), "status/diff 不得发送给模型后冒险自动放行");
    }

    @Test void commonStatusVariantsReachAiAndRunWithoutHelpersOrIndexWrites() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("ordinary-repo"));
        Path git = availableGit(root);
        command(git, root, "init", "--quiet");
        Files.writeString(root.resolve("tracked.txt"), "before\n");
        command(git, root, "add", "tracked.txt");
        command(git, root, "config", "core.fsmonitor", "echo helper > helper-invoked.txt");
        command(git, root, "config", "core.pager", "echo pager > pager-invoked.txt");
        Files.writeString(root.resolve("tracked.txt"), "after with changes\n");
        Files.writeString(root.resolve("new.txt"), "private-file-content-not-for-reviewer");
        Path index = root.resolve(".git/index");
        byte[] before = Files.readAllBytes(index);
        var timestamp = Files.getLastModifiedTime(index);
        var received = new ArrayList<dev.codeharness.core.model.ChatRequest>();
        var reviewer = new CommandReview(request -> { received.add(request); return ChatResponse.text("{\"decision\":\"approve\"}"); },
                Duration.ofSeconds(2), 16, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            var result = reviewer.review(request);
            assertTrue(result.approved(), result.reason());
            assertTrue(result.arguments().contains("--ignore-submodules=all"));
            return CommandApproval.Decision.direct(result.arguments());
        });
        List<String> variants = List.of("git status", "git status --short", "git status -s", "git status --porcelain",
                "git status --porcelain=v1", "git status --porcelain=v2", "git status -sb",
                "git status --short --branch", "git status --branch --short");
        for (String text : variants) {
            String result = tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(java.util.Map.of("command", text)));
            assertTrue(result.startsWith("退出码：0"), result);
            assertTrue(result.contains("tracked.txt") && result.contains("new.txt"), result);
        }
        assertEquals(variants.size(), received.size());
        for (var request : received) {
            assertTrue(request.tools().isEmpty());
            assertEquals(2, request.messages().size());
            assertTrue(request.messages().getFirst().content().contains("工作树状态"));
            assertFalse(request.messages().getLast().content().contains("private-file-content"));
        }
        assertFalse(Files.exists(root.resolve("helper-invoked.txt")));
        assertFalse(Files.exists(root.resolve("pager-invoked.txt")));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(timestamp, Files.getLastModifiedTime(index));
        assertTrue(reviewer.review(new CommandApproval.Request(root.toRealPath(), "git status --short")).approved());
        assertEquals(1, reviewer.stats().cacheHits());
        command(git, root, "config", "filter.new.clean", "echo must-not-run");
        var blocked = reviewer.review(new CommandApproval.Request(root.toRealPath(), "git status --short"));
        assertFalse(blocked.approved(), "修改仓库配置后不能复用旧批准");
        assertTrue(blocked.reason().contains("过滤器"));
        assertEquals(variants.size(), received.size());
    }

    @Test void aiCanDenyOrdinaryStatusAndChangedConfigurationInvalidatesItsApproval() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("review-change"));
        Path git = availableGit(root);
        command(git, root, "init", "--quiet");
        var request = new CommandApproval.Request(root.toRealPath(), "git status --short");
        var denied = new CommandReview(ignored -> ChatResponse.text("{\"decision\":\"manual\"}"),
                Duration.ofSeconds(2), 8, ignored -> {});
        assertFalse(denied.review(request).approved());
        assertEquals(1, denied.stats().requests(), "普通状态命令仍需 AI 审核，不是直接放行");
        var changing = new CommandReview(ignored -> {
            try { command(git, root, "config", "status.showUntrackedFiles", "no"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return ChatResponse.text("{\"decision\":\"approve\"}");
        }, Duration.ofSeconds(2), 8, ignored -> {});
        var result = changing.review(request);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("发生变化"));
    }

    @Test void unsafeGitOptionsAndExternalRepositoryPointersNeverReachReviewer() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repo"));
        Path git = availableGit(root);
        command(git, root, "init", "--quiet");
        AtomicInteger calls = new AtomicInteger();
        var reviewer = new CommandReview(request -> { calls.incrementAndGet(); return ChatResponse.text("{\"decision\":\"approve\"}"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        for (String text : List.of("git status && echo bad", "git -c core.fsmonitor=bad status", "git diff", "git show",
                "git diff --output=out.txt", "git diff --stat ../outside", "git -C .. status", "git push", "git log -p")) {
            assertFalse(reviewer.review(new CommandApproval.Request(root.toRealPath(), text)).approved(), text);
        }
        Path child = Files.createDirectory(root.resolve("child"));
        assertFalse(reviewer.review(new CommandApproval.Request(child.toRealPath(), "git status")).approved());
        Files.writeString(child.resolve(".git"), "gitdir: " + root.resolve(".git") + "\n");
        assertFalse(reviewer.review(new CommandApproval.Request(child.toRealPath(), "git status")).approved());
        Files.writeString(root.resolve(".git/commondir"), "../../outside\n");
        assertFalse(reviewer.review(new CommandApproval.Request(root.toRealPath(), "git status")).approved());
        assertEquals(0, calls.get());
    }

    private static Path availableGit(Path root) {
        Path git = ReadOnlyCommandGate.installedGit(root);
        assumeTrue(git != null, "本机未安装可供隔离验收的 Git");
        return git;
    }

    private static void command(Path git, Path root, String... args) throws Exception {
        var argv = new ArrayList<>(List.of(git.toString()));
        argv.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", windows ? "NUL" : "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", windows ? "NUL" : "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }
}
