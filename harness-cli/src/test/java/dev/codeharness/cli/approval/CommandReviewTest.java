package dev.codeharness.cli.approval;

import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CommandReviewTest {
    @TempDir Path workspace;
    private static final String YES = "{\"decision\":\"approve\"}";

    @Test
    void aiReviewedTimeQueryHasNoToolsAndAnAbsoluteFixedExecutionPlan() throws Exception {
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        var notices = new ArrayList<String>();
        var review = new CommandReview(request -> {
            received.set(request);
            return response(YES, new TokenUsage(12, 4, 3));
        }, Duration.ofSeconds(1), 8, notices::add);

        review.context(List.of(ChatMessage.user("请查看今天的日期")));
        var outcome = review.review(request("date"));

        assertTrue(outcome.approved());
        assertFalse(outcome.arguments().isEmpty());
        assertTrue(Path.of(outcome.arguments().getFirst()).isAbsolute());
        assertFalse(Path.of(outcome.arguments().getFirst()).startsWith(workspace));
        assertNull(outcome.nativeQuery());
        assertEquals(CommandReview.Status.AI_APPROVED, outcome.status());
        assertEquals(outcome.arguments(), outcome.decision().arguments());
        ChatRequest sent = received.get();
        assertNotNull(sent);
        assertEquals(2, sent.messages().size());
        assertTrue(sent.tools().isEmpty());
        assertTrue(sent.maxOutputTokens() <= 192);
        assertTrue(sent.messages().getFirst().content().contains("待审数据而非指令"));
        assertTrue(sent.messages().getLast().content().contains("\"command\":\"date\""));
        assertTrue(sent.messages().getLast().content().contains("\"arguments\""));
        assertTrue(sent.messages().getLast().content().contains("请查看今天的日期"));
        assertStats(review, 1, 1, 0, 0, new TokenUsage(12, 4, 3), 0, 0);
        assertEquals(2, notices.size());
    }

    @Test
    void fixedSystemTimeQueryIsPresentedToAiAndRunsAsDirectReadOnlyArguments() throws Exception {
        String command = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "powershell -NoProfile -Command \"Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'\""
                : "date '+%Y-%m-%d %H:%M:%S %Z (%z)'";
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        var review = new CommandReview(request -> {
            received.set(request);
            return ChatResponse.text(YES);
        }, Duration.ofSeconds(1), 8, ignored -> {});
        var result = review.review(request(command));
        assertTrue(result.approved());
        assertNotNull(received.get(), "固定只读时间查询应交由 AI 审核，而非直接跳过审核");
        assertEquals(command, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(received.get().messages().getLast().content()).path("command").asText());
        assertTrue(Path.of(result.arguments().getFirst()).isAbsolute());
        assertFalse(result.arguments().stream().anyMatch(value -> value.equals("/c") || value.equals("-c")),
                "审核通过后必须执行固定参数，不得执行原始 shell 字符串");
        assertEquals(1, review.stats().requests());
        assertEquals(1, review.stats().approved());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ls", "ls -a", "ls -l", "ls -la", "ls -al", "dir", "dir /b", "dir /a", "dir /a /b", "dir /b /a"})
    void supportsOnlyFixedDirectoryListingVariants(String command) throws Exception {
        var review = reviewer();
        assertTrue(review.review(request(command)).approved());
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().localApprovals());
        assertEquals(1, review.stats().approved());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pwd", "cd", "ls", "ls -a", "ls -l", "ls -la", "ls -al",
            "dir", "dir /b", "dir /a", "dir /a /b", "dir /b /a", "rg --files",
            "rg --files --hidden -g AGENTS.md -g '!.git'"})
    void trivialDirectoryQueriesDoNotDependOnReviewProvider(String command) throws Exception {
        var review = new CommandReview(request -> { throw new AssertionError("不应请求审核模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var outcome = review.review(request(command));
        assertTrue(outcome.approved());
        assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status());
        assertNotNull(outcome.nativeQuery());
        assertTrue(outcome.arguments().isEmpty());
        assertEquals(outcome.nativeQuery(), outcome.decision().nativeQuery());
        assertTrue(outcome.decision().approved());
        assertStats(review, 0, 1, 0, 0, TokenUsage.ZERO, 0, 1);
    }

    @Test
    void fixedFindAgentsQueryUsesNativePlanWithoutExternalRipgrepOrModel() throws Exception {
        String command = "find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'";
        var review = new CommandReview(request -> { throw new AssertionError("固定文件名查询不应请求审核模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var outcome = review.review(request(command));
        assertTrue(outcome.approved());
        assertNotNull(outcome.nativeQuery());
        assertEquals(command, outcome.nativeQuery().command());
        assertEquals(workspace.toRealPath(), outcome.nativeQuery().workspace());
        assertTrue(outcome.arguments().isEmpty());
        assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status());
        assertEquals(0, review.stats().requests());
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "date '+%Y-%m-%d %H:%M:%S %Z (%z)'", "date \"+%Y-%m-%d %H:%M:%S %z\"",
            "date +%Y-%m-%d", "date +%H:%M:%S", "date '+%Y-%m-%d %H:%M:%S'"})
    void dateReadQueriesReachAiOnEitherPlatform(String command) throws Exception {
        var review = reviewer();
        assertTrue(review.review(request(command)).approved());
        assertEquals(1, review.stats().requests());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Get-Date", "get-date -format 'yyyy-MM-dd'", "Get-Date -Format o", "Get-Date -Format s",
            "Get-Date -Format u", "Get-Date -Format HH:mm:ss", "Get-Date -Format \"yyyy-MM-dd HH:mm:ss\"",
            "powershell.exe -NoProfile -Command \"Get-Date\"",
            "powershell -NoProfile -Command 'Get-Date -Format \"yyyy-MM-dd HH:mm:ss zzz\"'"})
    void powerShellQueryHasNoDependencyOnProfilesOrWorkspaceExecutable(String command) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        for (String name : List.of("date.cmd", "powershell.cmd", "powershell.exe", "Get-Date.ps1"))
            Files.writeString(workspace.resolve(name), "must not execute");
        var outcome = reviewer().review(request(command));
        assertTrue(outcome.approved());
        assertFalse(Path.of(outcome.arguments().getFirst()).startsWith(workspace));
        assertTrue(outcome.arguments().containsAll(List.of("-NoProfile", "-NonInteractive")));
        assertTrue(outcome.arguments().getLast().contains("[DateTimeOffset]::"));
        assertFalse(outcome.arguments().getLast().contains("Get-Date"), "执行表达式不应依赖工作区函数或模块自动加载");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "pwd\n", "pwd\r\nls", "pwd\t", "pwd\u001b[31m",
            "pwd&whoami", "pwd > out.txt", "pwd < input.txt",
            "pwd $(id)", "pwd `id`", "pwd %PATH%", "pwd !PATH!", "p^wd", "pwd || del /s .", "pwd # comment",
            "ls ..", "ls ../secret", "ls C:\\", "ls .env", "ls /etc",
            "dir /s", "dir /b .", "cd ..", "cd /d C:\\", "mvn test", "npm test", "pytest",
            "git -c alias.x=!id x", "java -version", "java -jar app.jar",
            "rg --files --pre malicious", "rg --files --follow", "rg --files ../outside", "rg --files > files.txt",
            "rg --files --hidden -g AGENTS.md -g '!.git' && whoami",
            "powershell -File run.ps1", "powershell -NoProfile -Command \"Get-Date; Remove-Item *\"",
            "date -s 10:00", "date 10201000", "date +%Y-%m-%d > out.txt", "date && whoami",
            "Get-Date -Format 'yyyy-MM-dd' | Out-File x", "Get-Date -Format 'yyyy-MM-dd'; Remove-Item *",
            "powershell -NoProfile -Command \"Get-Date -Format $(whoami)\"",
            "powershell -NoProfile -Command \"Get-Date -Format 'yyyy-MM-dd'\" & echo injected",
            "date '+%Y-%m-%d %H:%M:%S %Z (%z) %n'", "date +%s", "cmd /c pwd", "./ls", ".\\dir.cmd", "curl https://example.com", "rm -rf .",
            "ls ＆ whoami", "ls\u0000", "ls -a\u2028rm -rf ."
    })
    void hardVetoNeverCallsModel(String command) throws Exception {
        AtomicInteger called = new AtomicInteger();
        var review = new CommandReview(request -> { called.incrementAndGet(); return ChatResponse.text(YES); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var result = review.review(request(command));
        assertFalse(result.approved());
        assertTrue(result.arguments().isEmpty());
        assertEquals(0, called.get());
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().manual());
    }

    @Test
    void boundedReadOnlyShellChainUsesLocalFastPathAndKeepsFixedArguments() throws Exception {
        createGitDirectory();
        org.junit.jupiter.api.Assumptions.assumeTrue(ReadOnlyCommandGate.installedGit(workspace) != null,
                "本机没有外部 Git 可执行文件");
        var review = new CommandReview(request -> { throw new AssertionError("固定只读链不应请求审核模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        String command = "git status --short && echo --- && git diff --stat | tail -20 && echo --- && git status --short | wc -l";
        var shellSyntax = ReviewableShell.parse(command);
        assertNotNull(shellSyntax);
        var gate = new ReadOnlyCommandGate();
        assertNull(TimeQuery.parse(command));
        assertNull(ReadOnlyQuery.ripgrep(command));
        assertNotNull(gate.shellArguments(workspace.toRealPath(), shellSyntax));
        var candidate = gate.check(request(command));
        assertTrue(candidate.plan().isPresent(), candidate.reason());

        var outcome = review.review(request(command));

        assertTrue(outcome.approved(), outcome.reason());
        assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status());
        assertEquals(0, review.stats().requests());
        assertEquals(1, review.stats().localApprovals());
        assertTrue(outcome.arguments().getLast().contains("git"));
        assertTrue(outcome.arguments().getLast().contains("tail"));
        assertTrue(outcome.arguments().getLast().contains("wc"));
        assertFalse(outcome.arguments().getLast().contains(";"));
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        assertEquals(windows ? 5 : 3, outcome.arguments().size());
    }

    @Test
    void structurallyReadableUnknownCommandReachesClassifierAndUsesBoundShellPlan() throws Exception {
        createGitDirectory();
        org.junit.jupiter.api.Assumptions.assumeTrue(ReadOnlyCommandGate.installedGit(workspace) != null,
                "本机没有外部 Git 可执行文件");
        AtomicReference<ChatRequest> received = new AtomicReference<>();
        var review = new CommandReview(request -> {
            received.set(request);
            return ChatResponse.text(YES);
        }, Duration.ofSeconds(1), 8, ignored -> {});

        var outcome = review.review(request("git show --stat HEAD"));

        assertTrue(outcome.approved(), outcome.reason());
        assertEquals(CommandReview.Status.AI_APPROVED, outcome.status());
        assertEquals(1, review.stats().requests());
        assertEquals(0, review.stats().localApprovals());
        assertTrue(Path.of(outcome.arguments().getFirst()).isAbsolute());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                received.get().messages().getLast().content());
        assertEquals("shell", data.path("actionType").asText());
        assertEquals("git show --stat HEAD", data.path("command").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "approve", "yes", "{\"decision\":true}", "{\"decision\":null}", "{\"decision\":\"APPROVE\"}",
            "{\"decision\":\"approve\",\"unknown\":\"safe\"}", "{\"decision\":\"manual\",\"decision\":\"approve\"}",
            "{\"decision\":\"approve\",\"reason\":true}", "{\"decision\":\"approve\",\"reason\":null}",
            "{\"decision\":\"approve\",\"reason\":\"a\",\"reason\":\"b\"}", "{\"reason\":\"safe\"}",
            "{\"decision\":\"approve\",\"decision\":\"approve\"}", "{\"decision\":\"approve\"}{}",
            "{\"decision\":\"approve\"} trailing", "[ {\"decision\":\"approve\"} ]", "{}",
            "```json\n{\"decision\":\"approve\"}\n```", "{\"Decision\":\"approve\"}", "{\"decision\":\"approve\",}"
    })
    void malformedAndExpandedSchemaFallBackWithoutLeakingModelReply(String reply) throws Exception {
        var notices = new ArrayList<String>();
        var review = new CommandReview(request -> response(reply, new TokenUsage(7, 2, 0)),
                Duration.ofSeconds(1), 8, notices::add);
        var outcome = review.review(request("date"));
        assertFalse(outcome.approved());
        assertEquals("自动审核返回无效，请人工确认", outcome.reason());
        assertEquals(CommandReview.Status.INVALID_RESPONSE, outcome.status());
        assertEquals(CommandApproval.Decision.denied(), outcome.decision());
        assertFalse(notices.contains(reply));
        assertEquals(new TokenUsage(7, 2, 0), review.stats().usage());
        assertEquals(0, review.stats().unreportedRequests());
    }

    @Test
    void manualToolAndTruncatedRepliesDoNotAuthorizeExecution() throws Exception {
        List<ChatResponse> replies = List.of(ChatResponse.text("{\"decision\":\"manual\"}"),
                new ChatResponse(YES, List.of(new ToolCall("r1", "run_command", "{}")), new TokenUsage(3, 2, 0), FinishReason.STOP),
                new ChatResponse(YES, List.of(), new TokenUsage(3, 2, 0), FinishReason.LENGTH));
        for (ChatResponse reply : replies) {
            var review = new CommandReview(request -> reply, Duration.ofSeconds(1), 8, ignored -> {});
            var outcome = review.review(request("date"));
            assertFalse(outcome.approved());
            assertEquals(reply.text().contains("manual") ? CommandReview.Status.AI_MANUAL
                    : CommandReview.Status.INVALID_RESPONSE, outcome.status());
            assertEquals(0, review.stats().approved());
        }
    }

    @Test
    void acceptsOptionalShortReasonButNeverDisplaysUntrustedModelText() throws Exception {
        String untrustedReason = "private-reason-value-\u001b[31m";
        var notices = new ArrayList<String>();
        String reply = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("reason", untrustedReason, "decision", "approve"));
        var review = new CommandReview(request -> response(reply, new TokenUsage(4, 3, 0)),
                Duration.ofSeconds(1), 8, notices::add);
        var outcome = review.review(request("date"));
        assertTrue(outcome.approved());
        assertEquals(CommandReview.Status.AI_APPROVED, outcome.status());
        assertFalse(outcome.reason().contains("private-reason-value"));
        assertFalse(notices.toString().contains("private-reason-value"));
        assertStats(review, 1, 1, 0, 0, new TokenUsage(4, 3, 0), 0, 0);
    }

    @Test
    void reasonLengthBoundaryIsEnforced() throws Exception {
        for (int length : List.of(160, 161)) {
            String reply = "{\"decision\":\"approve\",\"reason\":\"" + "安".repeat(length) + "\"}";
            var review = new CommandReview(request -> ChatResponse.text(reply),
                    Duration.ofSeconds(1), 8, ignored -> {});
            var outcome = review.review(request("date"));
            assertEquals(length == 160, outcome.approved());
            assertEquals(length == 160 ? CommandReview.Status.AI_APPROVED : CommandReview.Status.INVALID_RESPONSE,
                    outcome.status());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"git status", "git status --short", "git status --short --branch --untracked-files=all",
            "git branch --show-current"})
    void ordinaryGitQueriesUseLocalFixedPlansWithoutModel(String command) throws Exception {
        createGitDirectory();
        org.junit.jupiter.api.Assumptions.assumeTrue(ReadOnlyCommandGate.installedGit(workspace) != null,
                "本机没有外部 Git 可执行文件");
        var review = new CommandReview(request -> { throw new AssertionError("本地 Git 查询不应请求模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var outcome = review.review(request(command));
        assertTrue(outcome.approved());
        assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status());
        assertNull(outcome.nativeQuery());
        assertTrue(Path.of(outcome.arguments().getFirst()).isAbsolute());
        assertTrue(outcome.arguments().containsAll(List.of("--no-pager", "--no-optional-locks", "core.fsmonitor=false")));
        assertEquals(outcome.arguments(), outcome.decision().arguments());
        assertStats(review, 0, 1, 0, 0, TokenUsage.ZERO, 0, 1);
    }

    @Test
    void gitConfigurationFailureExplainsSafetyVetoWithoutCallingModel() throws Exception {
        createGitDirectory();
        org.junit.jupiter.api.Assumptions.assumeTrue(ReadOnlyCommandGate.installedGit(workspace) != null,
                "本机没有外部 Git 可执行文件");
        Files.writeString(workspace.resolve(".git/config"), "[include]\npath = ../outside\n");
        var review = new CommandReview(request -> { throw new AssertionError("安全检查失败不得请求模型"); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var outcome = review.review(request("git status --short"));
        assertFalse(outcome.approved());
        assertEquals(CommandReview.Status.SAFETY_CHECK, outcome.status());
        assertTrue(outcome.reason().contains("配置"));
        assertEquals(0, review.stats().requests());
    }

    @Test
    void explicitReportedZeroUsageIsNotCountedAsUnknown() throws Exception {
        var observed = new ArrayList<ChatResponse>();
        ChatResponse zero = new ChatResponse(YES, List.of(), TokenUsage.ZERO, FinishReason.STOP,
                Map.of("usage.inputReported", "true", "usage.outputReported", "true"));
        var review = new CommandReview(request -> zero, Duration.ofSeconds(1), 8, ignored -> {}, observed::add);
        assertTrue(review.review(request("date")).approved());
        assertEquals(List.of(zero), observed);
        assertStats(review, 1, 1, 0, 0, TokenUsage.ZERO, 0, 0);
    }

    @Test
    void taskResetRemovesHistoricalContextAndLocalAccounting() throws Exception {
        var received = new AtomicReference<ChatRequest>();
        var review = new CommandReview(request -> { received.set(request); return ChatResponse.text(YES); },
                Duration.ofSeconds(1), 8, ignored -> {});
        review.context(List.of(ChatMessage.user("previous-task-context")));
        assertTrue(review.review(request("pwd")).approved());
        review.beginTask();
        assertStats(review, 0, 0, 0, 0, TokenUsage.ZERO, 0, 0);
        assertTrue(review.review(request("date")).approved());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(received.get().messages().getLast().content());
        assertTrue(data.path("context").isArray());
        assertTrue(data.path("context").isEmpty());
        assertFalse(data.toString().contains("previous-task-context"));
    }

    @Test
    void successfulCacheIsBoundToExactCommandWorkspaceAndTask() throws Exception {
        AtomicInteger called = new AtomicInteger();
        var review = new CommandReview(request -> { called.incrementAndGet(); return response(YES, new TokenUsage(2, 1, 0)); },
                Duration.ofSeconds(1), 8, ignored -> {});
        var first = review.review(request("date"));
        var cached = review.review(request("date"));
        assertEquals(first.arguments(), cached.arguments());
        assertEquals(CommandReview.Status.CACHED_APPROVED, cached.status());
        assertTrue(review.review(request("date +%Y-%m-%d")).approved());
        Path another = Files.createDirectory(workspace.resolve("another")).toRealPath();
        assertTrue(review.review(new CommandApproval.Request(another, "date")).approved());
        assertEquals(3, called.get());
        assertStats(review, 3, 4, 1, 0, new TokenUsage(6, 3, 0), 0, 0);

        review.beginTask();
        assertStats(review, 0, 0, 0, 0, TokenUsage.ZERO, 0, 0);
        assertEquals(0, review.stats().elapsedMillis());
        assertTrue(review.review(request("date")).approved());
        assertEquals(4, called.get());
    }

    @Test
    void failedReviewIsNotRepeatedAndUniqueRequestLimitPreservesApprovedCache() throws Exception {
        AtomicInteger called = new AtomicInteger();
        var review = new CommandReview(request -> {
            called.incrementAndGet();
            return ChatResponse.text(called.get() == 1 ? "{\"decision\":\"manual\"}" : YES);
        }, Duration.ofSeconds(1), 2, ignored -> {});
        assertEquals(CommandReview.Status.AI_MANUAL, review.review(request("date")).status());
        assertEquals(CommandReview.Status.REPEATED, review.review(request("date")).status());
        assertTrue(review.review(request("date +%Y-%m-%d")).approved());
        assertEquals(CommandReview.Status.LIMIT_REACHED, review.review(request("date +%H:%M:%S")).status());
        assertTrue(review.review(request("date +%Y-%m-%d")).approved());
        assertEquals(2, called.get());
        assertStats(review, 2, 2, 1, 3, TokenUsage.ZERO, 2, 0);
        assertTrue(review.review(request("ls")).approved(), "AI 额度不足不影响本地查询");
        assertEquals(2, called.get());
    }

    @Test
    void timeoutIsBoundedAndUnreportedWithoutWaitingForIgnoringProvider() throws Exception {
        var review = new CommandReview(request -> {
            long stop = System.nanoTime() + Duration.ofMillis(700).toNanos();
            while (System.nanoTime() < stop) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) { /* 故意模拟不响应取消的厂商。 */ }
            }
            return response(YES, new TokenUsage(100, 100, 0));
        }, Duration.ofMillis(40), 8, ignored -> {});
        long start = System.nanoTime();
        var outcome = review.review(request("date"));
        assertFalse(outcome.approved());
        assertEquals(CommandReview.Status.TIMEOUT, outcome.status());
        assertTrue(System.nanoTime() - start < Duration.ofMillis(500).toNanos());
        assertStats(review, 1, 0, 0, 1, TokenUsage.ZERO, 1, 0);
        assertTrue(review.stats().elapsedMillis() >= 20);
        assertEquals(CommandReview.Status.REPEATED, review.review(request("date")).status());
        assertEquals(1, review.stats().requests());
    }

    @Test
    void exceptionAndNullReplyAreClosedAndNeverExposeExceptionText() throws Exception {
        var notices = new ArrayList<String>();
        var failed = new CommandReview(request -> { throw new IllegalStateException("private-key-value"); },
                Duration.ofSeconds(1), 8, notices::add);
        var failedOutcome = failed.review(request("date"));
        assertFalse(failedOutcome.approved());
        assertEquals(CommandReview.Status.PROVIDER_UNAVAILABLE, failedOutcome.status());
        assertFalse(failedOutcome.reason().contains("private-key-value"));
        assertFalse(notices.toString().contains("private-key-value"));
        assertEquals(1, failed.stats().unreportedRequests());
        var empty = new CommandReview(request -> null, Duration.ofSeconds(1), 8, ignored -> {});
        assertEquals(CommandReview.Status.INVALID_RESPONSE, empty.review(request("date")).status());
        assertEquals(1, empty.stats().unreportedRequests());
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "pwd"})
    void preInterruptedReviewCancelsBeforeModelOrLocalApprovalAndPreservesInterrupt(String command) throws Exception {
        var calls = new AtomicInteger();
        var observed = new ArrayList<ChatResponse>();
        var review = new CommandReview(request -> { calls.incrementAndGet(); return ChatResponse.text(YES); },
                Duration.ofSeconds(1), 8, ignored -> {}, observed::add);
        var candidate = request(command);
        try {
            Thread.currentThread().interrupt();
            var outcome = review.review(candidate);
            assertEquals(CommandReview.Status.CANCELLED, outcome.status());
            assertFalse(outcome.approved());
            assertEquals(CommandApproval.Decision.denied(), outcome.decision());
            assertTrue(Thread.currentThread().isInterrupted(), "审核不得清除调用线程的取消标志");
            assertEquals(0, calls.get());
            assertTrue(observed.isEmpty(), "未请求模型时不得记录未知模型用量");
            assertStats(review, 0, 0, 0, 1, TokenUsage.ZERO, 0, 0);
        } finally {
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptingWaitingReviewCancelsAndDiscardsLateApprovalAndUsage() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var providerThread = new AtomicReference<Thread>();
        var calls = new AtomicInteger();
        var observed = new ArrayList<ChatResponse>();
        var review = new CommandReview(request -> {
            calls.incrementAndGet();
            providerThread.set(Thread.currentThread());
            entered.countDown();
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { /* 模拟取消后仍返回成功的厂商。 */ }
            }
            return response(YES, new TokenUsage(100, 20, 50));
        }, Duration.ofSeconds(30), 8, ignored -> {}, observed::add);
        var candidate = request("date");
        var outcome = new AtomicReference<CommandReview.Outcome>();
        var failure = new AtomicReference<Throwable>();
        var interruptPreserved = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().daemon().name("审核取消回归夹具").unstarted(() -> {
            try {
                outcome.set(review.review(candidate));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                Thread.interrupted();
            }
        });
        caller.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "审核模型应已进入受控等待");
            caller.interrupt();
            caller.join(5_000);
            assertFalse(caller.isAlive(), "取消不能等待忽略中断的厂商结束");
            assertNull(failure.get());
            assertNotNull(outcome.get());
            assertEquals(CommandReview.Status.CANCELLED, outcome.get().status());
            assertFalse(outcome.get().approved());
            assertEquals(CommandApproval.Decision.denied(), outcome.get().decision());
            assertTrue(interruptPreserved.get());
            assertEquals(1, observed.size());
            assertNull(observed.getFirst(), "已发出的取消请求应记录一次未知用量");
            assertStats(review, 1, 0, 0, 1, TokenUsage.ZERO, 1, 0);

            release.countDown();
            providerThread.get().join(5_000);
            assertFalse(providerThread.get().isAlive(), "迟到响应必须已实际返回后再验证缓存与用量");
            assertEquals(1, observed.size(), "迟到成功不得再次上报用量");
            assertStats(review, 1, 0, 0, 1, TokenUsage.ZERO, 1, 0);
            var repeated = review.review(candidate);
            assertFalse(repeated.approved());
            assertEquals(CommandReview.Status.REPEATED, repeated.status(), "迟到成功不得写入批准缓存");
            assertEquals(1, calls.get());
            assertStats(review, 1, 0, 0, 2, TokenUsage.ZERO, 1, 0);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(5_000);
            Thread provider = providerThread.get();
            if (provider != null) provider.join(5_000);
        }
    }

    @Test
    void workspaceReplacementDuringReviewInvalidatesApproval() throws Exception {
        Path selected = Files.createDirectory(workspace.resolve("selected"));
        Path old = workspace.resolve("old");
        var review = new CommandReview(request -> {
            try { Files.move(selected, old); Files.createDirectory(selected); }
            catch (Exception problem) { throw new RuntimeException(problem); }
            return ChatResponse.text(YES);
        }, Duration.ofSeconds(1), 8, ignored -> {});
        var outcome = review.review(new CommandApproval.Request(selected.toRealPath(), "date"));
        assertFalse(outcome.approved());
        assertEquals("执行条件发生变化，请人工确认", outcome.reason());
        assertEquals(CommandReview.Status.CONDITIONS_CHANGED, outcome.status());
        assertEquals(0, review.stats().approved());
    }

    @Test
    void localBinaryShadowsAreNeverChosen() throws Exception {
        for (String filename : List.of("pwd", "ls", "dir.cmd", "cmd.exe", "git.cmd"))
            Files.writeString(workspace.resolve(filename), "malicious shadow executable");
        var review = reviewer();
        var result = review.review(request("ls"));
        assertTrue(result.approved());
        assertNotNull(result.nativeQuery());
        assertTrue(result.arguments().isEmpty());
        assertEquals(0, review.stats().requests());
    }

    @Test
    void symlinkWorkspaceAndFileWorkspaceNeverReachModel() throws Exception {
        var review = reviewer();
        Path regularFile = Files.writeString(workspace.resolve("file"), "data");
        assertFalse(review.review(new CommandApproval.Request(regularFile, "pwd")).approved());
        Path link = workspace.resolve("linked");
        try { Files.createSymbolicLink(link, workspace); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前平台不能创建符号链接");
        }
        assertFalse(review.review(new CommandApproval.Request(link, "pwd")).approved());
        assertEquals(0, review.stats().requests());
    }

    @Test
    void approvalValueObjectsDoNotAllowHiddenMutableExecutionPlans() {
        var source = new ArrayList<>(List.of("/bin/ls"));
        var decision = CommandApproval.Decision.direct(source);
        source.add("--dangerous");
        assertEquals(List.of("/bin/ls"), decision.arguments());
        assertThrows(UnsupportedOperationException.class, () -> decision.arguments().add("other"));
        assertThrows(IllegalArgumentException.class, () -> CommandApproval.Decision.direct(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CommandApproval.Decision(false, List.of("anything")));
        assertThrows(IllegalArgumentException.class, () -> CommandApproval.Decision.direct(List.of("")));
    }

    private CommandReview reviewer() {
        return new CommandReview(request -> ChatResponse.text(YES), Duration.ofSeconds(1), 8, ignored -> {});
    }

    private void createGitDirectory() throws Exception {
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(git.resolve("config"), "[core]\nrepositoryformatversion = 0\nbare = false\n");
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(git.resolve("objects"));
        Files.createDirectories(git.resolve("refs/heads"));
    }

    private static void assertStats(CommandReview review, int requests, int approved, int cacheHits, int manual,
                                    TokenUsage usage, int unreported, int local) {
        var stats = review.stats();
        assertAll(() -> assertEquals(requests, stats.requests()), () -> assertEquals(approved, stats.approved()),
                () -> assertEquals(cacheHits, stats.cacheHits()), () -> assertEquals(manual, stats.manual()),
                () -> assertEquals(usage, stats.usage()), () -> assertEquals(unreported, stats.unreportedRequests()),
                () -> assertEquals(local, stats.localApprovals()),
                () -> assertEquals(approved - cacheHits - local, stats.aiApprovals()),
                () -> assertTrue(stats.elapsedMillis() >= 0));
    }

    private CommandApproval.Request request(String command) throws Exception {
        return new CommandApproval.Request(workspace.toRealPath(), command);
    }

    private static ChatResponse response(String text, TokenUsage usage) {
        return new ChatResponse(text, List.of(), usage, FinishReason.STOP);
    }
}
