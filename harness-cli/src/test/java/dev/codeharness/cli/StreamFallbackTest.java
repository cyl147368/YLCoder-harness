package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 只用 localhost 响应验证重试次数、真实请求记账、取消及工具不重放。 */
class StreamFallbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ERROR = "data: {\"error\":{\"message\":\"private-upstream-payload\"}}\n\n";
    @TempDir Path workspace;
    private HttpServer server;
    private Path config;
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private boolean cancelDuringWait;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                int index = requests.size() - 1;
                Reply reply = index < replies.size() ? replies.get(index) : new Reply(500, "application/json", "{}");
                byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", reply.type);
                exchange.sendResponseHeaders(reply.status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        config = workspace.resolve("offline.yaml");
        Files.writeString(config, "provider: openai-compatible\nmodel: fallback-fixture\napi-key: private-upstream-payload\napi-key-env: ''\nbase-url: http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1\npermissions:\n  auto-review: false\n");
    }

    @AfterEach void stop() { server.stop(0); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneFallbackKeepsSameRequestAndCountsBothAttemptsWithoutPollutingJson(boolean http400) throws Exception {
        replies.add(recoverableFailure(http400));
        replies.add(answer());
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), delays);
        assertTrue(requests.getFirst().path("stream").asBoolean());
        assertFalse(requests.getLast().path("stream").asBoolean());
        assertSameRequest(0, 1);
        JsonNode output = JSON.readTree(result.out);
        assertEquals("最终回复", output.path("answer").asText());
        assertEquals(1, output.path("iterations").asInt());
        assertEquals(2, output.path("usageReport").path("requests").asInt());
        assertEquals(1, output.path("usageReport").path("inputReportedRequests").asInt());
        assertEquals(11, output.path("usageReport").path("total").path("inputTokens").asInt());
        assertFalse(result.out.contains("private-upstream-payload"));
        assertTrue(result.err.isBlank(), result.err);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void threeFailuresStopAndLeaveARecoverableCheckpoint(boolean http400) throws Exception {
        replies.add(recoverableFailure(http400));
        for (int i = 0; i < 3; i++) replies.add(new Reply(http400 ? 400 : 503, "application/json",
                "{\"error\":{\"message\":\"模型暂不可用 private-upstream-payload\",\"code\":\"upstream_unavailable\"}}"));
        Result result = run(new Input("检查项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(4, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertTrue(result.err.contains("重试 3 次，仍未成功"), result.err);
        assertTrue(result.err.contains("模型暂不可用"), result.err);
        assertTrue(result.err.contains("code=upstream_unavailable"), result.err);
        for (int i = 1; i <= 3; i++) assertSameRequest(0, i);
        assertFalse(result.err.contains("private-upstream-payload"));
        var saved = new SessionStore(workspace).recent(20);
        assertEquals(1, saved.size());
        assertEquals("FAILED", saved.getFirst().status());
        try (var files = Files.walk(workspace.resolve(".harness"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains("private-upstream-payload"));
            }
        }
    }

    @Test void 第三次重试成功并逐次记账() throws Exception {
        replies.add(recoverableFailure(true));
        replies.add(recoverableFailure(true));
        replies.add(recoverableFailure(true));
        replies.add(answer());
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(0, result.code, result.err);
        assertEquals(4, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertEquals(4, JSON.readTree(result.out).path("usageReport").path("requests").asInt());
        assertTrue(result.err.isBlank());
    }

    @Test void 等待期间取消不再请求并保存检查点() throws Exception {
        replies.add(recoverableFailure(true));
        cancelDuringWait = true;
        Result result = run(new Input("检查项目", "/exit"), false);
        assertEquals(130, result.code, result.err);
        assertEquals(1, requests.size());
        assertEquals(List.of(1000L), delays);
        assertEquals("CANCELLED", new SessionStore(workspace).recent(20).getFirst().status());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void json失败保留脱敏诊断且不输出进度() throws Exception {
        for (int i = 0; i < 4; i++) replies.add(new Reply(400, "application/json",
                "{\"error\":{\"message\":\"不支持参数 private-upstream-payload\",\"param\":\"max_tokens\"}}"));
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(1, result.code);
        JsonNode output = JSON.readTree(result.out);
        assertTrue(output.path("error").asText().contains("param=max_tokens"), result.out);
        assertTrue(output.path("error").asText().contains("不支持参数"));
        assertFalse(result.out.contains("private-upstream-payload"));
        assertEquals(4, output.path("usageReport").path("requests").asInt());
        assertTrue(result.err.isBlank(), result.err);
    }

    @Test void partialTextIsNotAutomaticallyDuplicatedAndLaterTaskUsesSync() throws Exception {
        replies.add(sse(event(Map.of("content", "部分回复"), null) + ERROR));
        replies.add(answer());
        Result result = run(new Input("原任务", "后续任务", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertTrue(result.err.contains("已输出部分回复"));
        assertFalse(requests.getLast().path("stream").asBoolean());
        assertEquals(2, requests.getLast().path("messages").findValuesAsText("role").stream().filter("user"::equals).count());
        assertEquals(1, result.out.split("部分回复", -1).length - 1);
        assertFalse(requests.getLast().path("messages").toString().contains("部分回复"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configurationCanDisableFallback(boolean http400) throws Exception {
        Files.writeString(config, Files.readString(config) + "agent:\n  stream-fallback: false\n");
        replies.add(recoverableFailure(http400));
        Result result = run(new Input("检查项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertTrue(result.err.contains("配置已关闭自动降级重试"));
    }

    @Test void malformedChunksAreNeverRetried() throws Exception {
        replies.add(sse("data: {}\n\n"));
        Result result = run(new Input("检查项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertTrue(result.err.contains("分块结构无效"));
    }

    @Test void explicitRequestErrorsAreNeverRetried() throws Exception {
        replies.add(new Reply(401, "application/json", "{}"));
        replies.add(sse("data: {\"error\":{\"code\":\"insufficient_quota\"}}\n\n"));
        Result result = run(new Input("任务一", "任务二", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.path("stream").asBoolean()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialToolFragmentsAreDiscardedAndEarlierCompletedToolsAreNotReplayed(boolean http400) throws Exception {
        replies.add(sse(event(tool("committed", "kept.txt"), "tool_calls") + "data: [DONE]\n\n"));
        replies.add(http400 ? recoverableFailure(true) : sse(event(tool("uncommitted", "must-not-exist.txt"), null) + ERROR));
        replies.add(answer());
        Result result = run(new Input("修改一次", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(3, requests.size());
        assertSameRequest(1, 2);
        assertEquals("唯一修改", Files.readString(workspace.resolve("kept.txt")));
        assertFalse(Files.exists(workspace.resolve("must-not-exist.txt")));
        var store = new SessionStore(workspace);
        var history = store.load(store.recent(20).getFirst().id()).transcript();
        assertEquals(1, history.stream().filter(message -> message.role() == dev.codeharness.core.model.ChatMessage.Role.TOOL).count());
        assertFalse(requests.getLast().toString().contains("uncommitted"));
        assertTrue(result.out.contains("3次主模型"), result.out);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validFallbackToolExecutesOnceAndNextRoundRemainsNonStreaming(boolean http400) throws Exception {
        replies.add(recoverableFailure(http400));
        var message = new java.util.LinkedHashMap<String, Object>(tool("valid", "created.txt"));
        message.put("role", "assistant");
        replies.add(new Reply(200, "application/json", JSON.writeValueAsString(Map.of("choices",
                List.of(Map.of("message", message, "finish_reason", "tool_calls"))))));
        replies.add(answer());
        Result result = run(new Input("修改一次", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(3, requests.size());
        assertTrue(requests.subList(1, 3).stream().noneMatch(request -> request.path("stream").asBoolean()));
        assertEquals("唯一修改", Files.readString(workspace.resolve("created.txt")));
        assertEquals(1, requests.getLast().path("messages").findValuesAsText("role").stream().filter("tool"::equals).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ctrlCAtRetryNoticeCancelsBeforeAnotherRequest(boolean http400) throws Exception {
        replies.add(recoverableFailure(http400));
        Result result = run(new Input("检查项目", "/exit"), true);
        assertEquals(130, result.code, result.err);
        assertEquals(1, requests.size());
        assertEquals("CANCELLED", new SessionStore(workspace).recent(20).getFirst().status());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void 非流式请求返回400时不重试() {
        replies.add(recoverableFailure(true));
        Result result = run(new Input("检查项目", "/exit"), false, "--no-stream");
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertFalse(requests.getFirst().path("stream").asBoolean());
        assertFalse(result.err.contains("正在自动改用"));
        assertFalse(result.err.contains("private-upstream-payload"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 413, 422})
    void 其他请求错误不误触发400降级(int status) {
        replies.add(new Reply(status, "application/json", "{\"error\":\"private-upstream-payload\"}"));
        Result result = run(new Input("检查项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertFalse(result.err.contains("正在自动改用"));
        assertFalse(result.err.contains("private-upstream-payload"));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void 临时HTTP失败按递增间隔重试并保留流式模式(int status) throws Exception {
        replies.add(new Reply(status, "application/json", "{\"error\":\"temporary failure\"}"));
        replies.add(new Reply(status, "application/json", "{\"error\":\"temporary failure\"}"));
        replies.add(answer());
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(0, result.code, result.err);
        assertEquals(List.of(1000L, 2000L), delays);
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.path("stream").asBoolean()));
        assertEquals(requests.getFirst(), requests.get(1), "临时失败重试应保留完整请求");
        assertEquals(requests.getFirst(), requests.get(2));
        assertEquals(3, JSON.readTree(result.out).path("usageReport").path("requests").asInt());
    }

    @Test void 暂时失败转流式兼容降级共享同一个三次重试预算() throws Exception {
        replies.add(new Reply(503, "application/json", "{}"));
        replies.add(recoverableFailure(true));
        replies.add(new Reply(503, "application/json", "{}"));
        replies.add(answer());
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(0, result.code, result.err);
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertEquals(4, requests.size());
        assertTrue(requests.get(1).path("stream").asBoolean());
        assertFalse(requests.get(2).path("stream").asBoolean());
        assertFalse(requests.get(3).path("stream").asBoolean());
    }

    @Test void 临时失败后的认证失败立即停止而不继续消耗重试预算() throws Exception {
        replies.add(new Reply(503, "application/json", "{}"));
        replies.add(new Reply(401, "application/json", "{\"error\":\"authentication failed\"}"));
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(1, result.code);
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), delays);
        assertTrue(JSON.readTree(result.out).path("error").asText().contains("401"));
    }

    @Test void 暂时性错误耗尽预算也不关闭后续任务的流式输出() throws Exception {
        for (int index = 0; index < 4; index++) replies.add(new Reply(503, "application/json", "{}"));
        replies.add(answer());
        Result result = run(new Input("检查项目", "继续任务", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(5, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertTrue(requests.stream().allMatch(request -> request.path("stream").asBoolean()));
        assertTrue(result.err.contains("模型请求已自动重试 3 次"), result.err);
    }

    @Test void 兼容降级期间遇到认证错误也立即停止() throws Exception {
        replies.add(recoverableFailure(true));
        replies.add(new Reply(401, "application/json", "{}"));
        Result result = run(new Input(), false, "--json", "run", "检查项目");
        assertEquals(1, result.code);
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), delays);
        assertFalse(requests.getLast().path("stream").asBoolean());
    }

    private static Reply recoverableFailure(boolean http400) {
        return http400 ? new Reply(400, "application/json", "{\"error\":\"private-upstream-payload\"}") : sse(ERROR);
    }

    private void assertSameRequest(int first, int second) {
        var left = requests.get(first).deepCopy();
        var right = requests.get(second).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) left).remove("stream");
        ((com.fasterxml.jackson.databind.node.ObjectNode) right).remove("stream");
        assertEquals(left, right, "重试不追加用户消息，不修改历史、工具定义或模型参数");
    }

    private static Reply sse(String body) { return new Reply(200, "text/event-stream", body); }
    private static Reply answer() throws Exception {
        return new Reply(200, "application/json", JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", "最终回复"), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 3))));
    }
    private static Map<String, Object> tool(String id, String path) throws Exception {
        return Map.of("tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function",
                Map.of("name", "write_file", "arguments", JSON.writeValueAsString(Map.of("path", path, "content", "唯一修改"))))));
    }
    private static String event(Map<String, Object> delta, String finish) throws Exception {
        var choice = new java.util.LinkedHashMap<String, Object>();
        choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", finish);
        return "data: " + JSON.writeValueAsString(Map.of("choices", List.of(choice))) + "\n\n";
    }

    private Result run(Input input, boolean cancelAtNotice, String... extra) {
        var cli = new CommandLine(new HarnessCli(input, millis -> {
            delays.add(millis);
            if (cancelDuringWait) input.cancel.run();
        }));
        var out = new StringWriter();
        var err = new StringWriter() {
            @Override public void write(String value, int offset, int length) {
                super.write(value, offset, length);
                if (cancelAtNotice && value.substring(offset, offset + length).contains("正在自动改用")) input.cancel.run();
            }
        };
        cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        cli.setExecutionExceptionHandler((failure, cmd, parsed) -> { cmd.getErr().println(failure.getMessage()); return 1; });
        var args = new ArrayList<>(List.of("--workspace", workspace.toString(), "--config", config.toString(), "--no-color"));
        args.addAll(List.of(extra));
        return new Result(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }
    private record Reply(int status, String type, String body) {}
    private record Result(int code, String out, String err) {}
    private static final class Input implements TerminalInput {
        private final java.util.Iterator<String> lines;
        private Runnable cancel = () -> { };
        Input(String... lines) { this.lines = List.of(lines).iterator(); }
        public boolean isTerminal() { return false; }
        public String readLine() { return lines.hasNext() ? lines.next() : null; }
        public Runnable onInterrupt(Runnable handler) { cancel = handler; return () -> cancel = () -> { }; }
    }
}
