package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TraceRecorderTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("每次真实调用都有独立编号，重复提示词只引用一次，调用参数和结果保持原对象")
    void recordsEveryAttemptWithoutChangingRequestOrResponse() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        ChatRequest request = new ChatRequest(List.of(ChatMessage.system("重复系统提示词标记"), ChatMessage.user("请核对编译结果")),
                List.of(new ToolDefinition("read_file", "读取指定文件的内容", Map.of("type", "object"))), 256);
        ChatResponse response = ChatResponse.text("模型回复保持原样");
        var calls = new ArrayList<ChatRequest>();
        ChatModel model = recorder.wrap(actual -> { calls.add(actual); return response; }, "agent", "custom-provider", "fixture-model");

        recorder.begin("same-request");
        recorder.iteration(3);
        assertSame(response, model.complete(request));
        assertSame(response, model.complete(request));
        recorder.finish("COMPLETED", null);

        assertEquals(2, calls.size());
        assertSame(request, calls.getFirst());
        assertSame(request, calls.getLast());
        String log = read(recorder, warnings);
        assertEquals(List.of("1", "2"), requestIds(log));
        assertEquals(1, occurrences(log, "重复系统提示词标记"));
        assertTrue(log.contains("首次记录"), log);
        assertTrue(log.contains("custom-provider"), log);
        assertTrue(log.contains("fixture-model"), log);
        assertTrue(log.contains("read_file"), log);
        assertTrue(log.contains("读取指定文件的内容"), log);
        assertTrue(log.contains("256"), log);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("失败和后续重试分别留痕，异常本身仍交给原调用方")
    void recordsFailedAttemptAndSuccessfulRetry() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        ChatRequest request = request("重试相同的提示词");
        IllegalStateException failure = new IllegalStateException("fixture gateway closed");
        AtomicInteger count = new AtomicInteger();
        ChatModel wrapped = recorder.wrap(actual -> {
            assertSame(request, actual);
            if (count.getAndIncrement() == 0) throw failure;
            return ChatResponse.text("重试后的完整回复");
        }, "agent", "fixture", "retry-model");

        recorder.begin("retry");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> wrapped.complete(request)));
        assertEquals("重试后的完整回复", wrapped.complete(request).text());
        recorder.finish("COMPLETED", null);

        String log = read(recorder, warnings);
        assertEquals(List.of("1", "2"), requestIds(log));
        assertTrue(log.contains("fixture gateway closed"), log);
        assertTrue(log.contains("IllegalStateException"), log);
        assertTrue(log.contains("重试后的完整回复"), log);
        assertTrue(log.indexOf("fixture gateway closed") < log.indexOf("重试后的完整回复"), log);
        assertEquals(1, occurrences(log, "重试相同的提示词"));
        assertEquals(2, count.get());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("流式断开时保留已显示片段和错误，原流式回调不被改变")
    void recordsPartialStreamAndFailureWithoutChangingVisibleDeltas() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        ChatRequest request = request("检查流式中断");
        IllegalStateException failure = new IllegalStateException("fixture stream closed");
        List<String> chunks = List.of("第一段", "第二段");
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse complete(ChatRequest actual) { fail("本测试必须调用流式接口"); return null; }
            @Override public ChatResponse complete(ChatRequest actual, Consumer<String> listener) {
                assertSame(request, actual);
                chunks.forEach(listener);
                throw failure;
            }
        };
        ChatModel wrapped = recorder.wrap(delegate, "agent", "fixture", "stream-model");
        var visible = new ArrayList<String>();

        recorder.begin("stream-failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> wrapped.complete(request, visible::add)));
        recorder.finish("ERROR", failure.getMessage());

        assertEquals(chunks, visible);
        String log = read(recorder, warnings);
        assertEquals(List.of("1"), requestIds(log));
        assertTrue(log.contains("第一段第二段"), log);
        assertTrue(log.contains("fixture stream closed"), log);
        assertTrue(log.contains("未完成"), log);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("审核线程延迟返回时仍写入原任务，不串入同一会话的下一任务")
    void reviewerReturnAfterNextTaskBeginsStaysInOriginalTask() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        ChatResponse reviewed = ChatResponse.text("{\"decision\":\"approve\",\"reason\":\"第一任务审核完成标记\"}");
        ChatModel reviewer = recorder.wrap(actual -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放审核回复");
                return reviewed;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }, "reviewer", "review-provider", "review-model");
        ChatModel agent = recorder.wrap(actual -> ChatResponse.text("第二任务答案标记"), "agent", "main-provider", "main-model");
        recorder.begin("shared-session");
        recorder.iteration(4);
        Thread thread = Thread.ofPlatform().name("trace-review-fixture").start(() -> {
            try { result.complete(reviewer.complete(request("第一任务审核提示词标记"))); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        Path first;
        Path second;
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "审核调用应在任务切换前开始");
            recorder.finish("CANCELLED", null);
            first = recorder.latestPath();
            recorder.begin("shared-session");
            recorder.iteration(1);
            assertEquals("第二任务答案标记", agent.complete(request("第二任务用户提示词标记")).text());
            recorder.finish("COMPLETED", null);
            second = recorder.latestPath();
            release.countDown();
            assertSame(reviewed, result.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            thread.join(10_000);
            recorder.finish("INTERRUPTED", null);
        }

        assertFalse(thread.isAlive(), "审核线程必须在测试结束前退出");
        assertNotNull(first, warnings.toString());
        assertNotNull(second, warnings.toString());
        assertNotEquals(first, second, "同一会话的不同任务应保留独立日志");
        String firstLog = Files.readString(first), secondLog = Files.readString(second);
        assertTrue(firstLog.contains("reviewer"), firstLog);
        assertTrue(firstLog.contains("review-provider"), firstLog);
        assertTrue(firstLog.contains("review-model"), firstLog);
        assertTrue(firstLog.contains("第一任务审核提示词标记"), firstLog);
        assertTrue(firstLog.contains("第一任务审核完成标记"), firstLog);
        assertFalse(firstLog.contains("第二任务用户提示词标记"), firstLog);
        assertFalse(firstLog.contains("第二任务答案标记"), firstLog);
        assertTrue(secondLog.contains("第二任务用户提示词标记"), secondLog);
        assertTrue(secondLog.contains("第二任务答案标记"), secondLog);
        assertFalse(secondLog.contains("reviewer"), secondLog);
        assertFalse(secondLog.contains("第一任务审核提示词标记"), secondLog);
        assertFalse(secondLog.contains("第一任务审核完成标记"), secondLog);
        assertEquals(2, TraceFiles.recent(workspace, "shared-session", 10).size());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("工具参数和失败结果关联同一调用，长结果保留摘要并标明截断")
    void recordsToolCallsAndBoundedErrorResults() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        HarnessConfig.Trace config = new HarnessConfig.Trace();
        config.toolResultMaxChars = 128;
        var recorder = new TraceRecorder(workspace, config, List.of(), warnings::add);
        ToolCall call = new ToolCall("tool-fixture-1", "read_file", "{\"path\":\"src/fixture.java\"}");
        String result = "工具失败摘要标记" + "x".repeat(1024) + "隐藏的结果尾部标记";

        recorder.begin("tool-error");
        recorder.toolStart(2, call);
        recorder.toolResult(2, call, result, true);
        recorder.finish("ERROR", "工具失败");

        String log = read(recorder, warnings);
        assertTrue(log.contains("tool-fixture-1"), log);
        assertTrue(log.contains("read\\_file") || log.contains("read_file"), log);
        assertTrue(log.contains("src/fixture.java"), log);
        assertTrue(log.contains("工具失败摘要标记"), log);
        assertFalse(log.contains("隐藏的结果尾部标记"), log);
        assertTrue(log.contains("截断"), log);
        assertTrue(log.contains(Integer.toString(result.length())), log);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("延迟注册的审核密钥在请求、工具参数和错误中均被脱敏")
    void addedSecretsAreRedactedWithoutChangingModelInput() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of("fixture-primary-value"), warnings::add);
        String laterSecret = "fixture-review-value";
        ChatRequest request = request("主凭据 fixture-primary-value；审核凭据 " + laterSecret);
        ChatModel wrapped = recorder.wrap(actual -> {
            assertSame(request, actual);
            assertTrue(actual.messages().getFirst().content().contains(laterSecret));
            return ChatResponse.text("测试回复 " + laterSecret);
        }, "agent", "fixture", "redaction-model");

        recorder.begin("redaction");
        recorder.addSecrets(List.of(laterSecret));
        wrapped.complete(request);
        ToolCall call = new ToolCall("secret-tool", "read_file", "{\"path\":\"ordinary.java\",\"note\":\"" + laterSecret + "\"}");
        recorder.toolStart(1, call);
        recorder.toolResult(1, call, "读取失败 " + laterSecret, true);
        recorder.finish("ERROR", "任务失败 " + laterSecret);

        String log = read(recorder, warnings);
        assertFalse(log.contains("fixture-primary-value"), log);
        assertFalse(log.contains(laterSecret), log);
        assertTrue(log.contains("已脱敏"), log);
        assertTrue(log.contains("ordinary.java"), log);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("禁用记录时模型和工具流程照常执行且不建立日志目录")
    void disabledTraceDoesNotCreateDirectoriesOrChangeCalls() throws Exception {
        Path workspace = workspace();
        HarnessConfig.Trace config = new HarnessConfig.Trace();
        config.enabled = false;
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, config, List.of(), warnings::add);
        ChatRequest request = request("关闭记录后的任务");
        ChatResponse response = ChatResponse.text("关闭记录后的回复");
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = recorder.wrap(actual -> { assertSame(request, actual); calls.incrementAndGet(); return response; },
                "agent", "fixture", "disabled-model");

        recorder.begin("disabled");
        recorder.iteration(1);
        assertSame(response, model.complete(request));
        ToolCall tool = new ToolCall("disabled-tool", "read_file", "{}");
        recorder.toolStart(1, tool);
        recorder.toolResult(1, tool, "本地结果", false);
        recorder.finish("COMPLETED", null);

        assertEquals(1, calls.get());
        assertNull(recorder.latestPath());
        assertFalse(Files.exists(workspace.resolve(".harness")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("任务未开始时包装器直接调用模型，不产生隐式记录")
    void wrapperWithoutActiveTaskDoesNotCreateTrace() throws Exception {
        Path workspace = workspace();
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        ChatRequest request = request("未开始任务");
        ChatResponse response = ChatResponse.text("透传回复");
        ChatModel wrapped = recorder.wrap(actual -> { assertSame(request, actual); return response; }, "agent", "fixture", "model");
        assertSame(response, wrapped.complete(request));
        assertNull(recorder.latestPath());
        assertFalse(Files.exists(workspace.resolve(".harness")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("目录不可写时只警告一次，仍保留原模型的请求和返回行为")
    void unavailableTraceWarnsOnceWithoutFailingModelAcrossTasks() throws Exception {
        Path workspace = workspace();
        Files.createDirectory(workspace.resolve(".harness"));
        Files.writeString(workspace.resolve(".harness/logs"), "此文件阻止建立同名目录");
        var warnings = new CopyOnWriteArrayList<String>();
        var recorder = new TraceRecorder(workspace, new HarnessConfig.Trace(), List.of(), warnings::add);
        ChatResponse response = ChatResponse.text("日志失败仍可运行");
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = recorder.wrap(request -> { calls.incrementAndGet(); return response; }, "agent", "fixture", "model");
        for (String session : List.of("first", "second")) {
            recorder.begin(session);
            assertSame(response, model.complete(request("测试日志降级")));
            recorder.finish("COMPLETED", null);
        }
        assertEquals(2, calls.get());
        assertEquals(1, warnings.size(), warnings.toString());
        assertNull(recorder.latestPath());
        assertEquals("此文件阻止建立同名目录", Files.readString(workspace.resolve(".harness/logs")));
    }

    private Path workspace() throws Exception { return Files.createDirectory(temporary.resolve("workspace")).toRealPath(); }
    private static ChatRequest request(String text) { return new ChatRequest(List.of(ChatMessage.user(text)), List.of(), 64); }
    private static String read(TraceRecorder recorder, List<String> warnings) throws Exception {
        Path path = recorder.latestPath();
        assertNotNull(path, "应保存可查看的执行日志；警告：" + warnings);
        return Files.readString(path);
    }
    private static List<String> requestIds(String log) {
        var matches = Pattern.compile("<a id=\"request-([0-9]+)\"></a>").matcher(log);
        var result = new ArrayList<String>();
        while (matches.find()) result.add(matches.group(1));
        return result;
    }
    private static int occurrences(String text, String needle) {
        int count = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }
}
