package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP 正文截断触发 I/O 错误；用注入等待验证 CLI 协议、重试与副作用边界。 */
class StreamTransportFallbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "transport-fixture-private-key";
    private static final String ANSWER = "断流恢复后的完整回复";
    @TempDir Path workspace;
    private HttpServer server;
    private ExecutorService serverThreads;
    private Path config;
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> serverErrors = new CopyOnWriteArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private boolean cancelDuringWait;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/", this::respond);
        server.start();
        config = workspace.resolve("offline.yaml");
    }

    @AfterEach void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
        assertTrue(serverErrors.isEmpty(), serverErrors.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 无文字的正文截断自动使用同一协议重试并保持JSON输出纯净(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(": 本地保活\n\n"));
        replies.add(answer(format));
        Result result = run(new Input(), false, "--json", "run", "检查当前项目");
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), delays);
        assertFallbackRequest(0, 1, format);
        JsonNode report = JSON.readTree(result.out);
        assertEquals(ANSWER, report.path("answer").asText());
        assertEquals(1, report.path("iterations").asInt());
        assertEquals(2, report.path("usageReport").path("requests").asInt());
        assertEquals(1, report.path("usageReport").path("inputReportedRequests").asInt());
        assertEquals(11, report.path("usageReport").path("total").path("inputTokens").asInt());
        assertEquals(3, report.path("usageReport").path("total").path("outputTokens").asInt());
        assertTrue(result.err.isBlank(), result.err);
        assertNoKey(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 连续传输故障在第三次重试成功且逐次记账(String format) throws Exception {
        configure(format);
        replies.add(brokenSse("\n"));
        replies.add(brokenJson());
        replies.add(brokenJson());
        replies.add(answer(format));
        Result result = run(new Input(), false, "--json", "run", "检查当前项目");
        assertEquals(0, result.code, result.err);
        assertEquals(4, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        for (int retry = 1; retry < 4; retry++) assertFallbackRequest(0, retry, format);
        JsonNode report = JSON.readTree(result.out);
        assertEquals(ANSWER, report.path("answer").asText());
        assertEquals(4, report.path("usageReport").path("requests").asInt());
        assertTrue(result.err.isBlank(), result.err);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 持续传输故障只重试三次并显示明确原因(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(": keepalive\n\n"));
        for (int i = 0; i < 3; i++) replies.add(brokenJson());
        Result result = run(new Input("检查当前项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(4, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertTrue(result.err.contains("重试 3 次，仍未成功"), result.err);
        assertTrue(result.err.contains("未收到完整响应"), result.err);
        assertTrue(result.err.contains("IOException") || result.err.contains("EOFException"), result.err);
        for (int retry = 1; retry < 4; retry++) assertFallbackRequest(0, retry, format);
        assertEquals("FAILED", new SessionStore(workspace).recent(20).getFirst().status());
        assertNoKey(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void JSON模式持续传输故障保留原因及请求次数但不混入进度(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(": keepalive\n\n"));
        for (int i = 0; i < 3; i++) replies.add(brokenJson());
        Result result = run(new Input(), false, "--json", "run", "检查当前项目");
        assertEquals(1, result.code);
        assertEquals(4, requests.size());
        JsonNode report = JSON.readTree(result.out);
        assertTrue(report.path("error").asText().contains("未收到完整响应"), result.out);
        assertTrue(report.path("error").asText().contains("重试 3 次，仍未成功"), result.out);
        assertEquals(4, report.path("usageReport").path("requests").asInt());
        assertTrue(result.err.isBlank(), result.err);
        assertNoKey(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 无文字响应超过总时限后同步重试成功(String format) throws Exception {
        configure(format);
        Files.writeString(config, Files.readString(config) + "timeout-seconds: 1\n");
        replies.add(new Reply("text/event-stream", ": 等待模型\n\n", true, 3000));
        replies.add(answer(format));
        Result result = run(new Input("检查当前项目", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertEquals(List.of(1000L), delays);
        assertTrue(result.err.contains("超时"), result.err);
        assertTrue(result.out.contains(ANSWER), result.out);
        assertFallbackRequest(0, 1, format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 已输出文字时不自动重试且后续任务沿用非流式(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(textDelta(format, "部分回复")));
        replies.add(answer(format));
        Result result = run(new Input("原任务", "后续任务", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertTrue(delays.isEmpty());
        assertTrue(requests.getFirst().path("stream").asBoolean());
        assertFalse(requests.getLast().path("stream").asBoolean());
        assertTrue(result.err.contains("已输出部分回复"), result.err);
        assertEquals(1, result.out.split("部分回复", -1).length - 1);
        JsonNode messages = requests.getLast().path(format.equals("chat") ? "messages" : "input");
        assertEquals(2, messages.findValuesAsText("role").stream().filter("user"::equals).count());
        assertFalse(messages.toString().contains("部分回复"));
        assertEndpoint(format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 等待重试时取消不会产生新请求(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(": keepalive\n\n"));
        cancelDuringWait = true;
        Result result = run(new Input("检查当前项目", "/exit"), false);
        assertEquals(130, result.code, result.err);
        assertEquals(1, requests.size());
        assertEquals(List.of(1000L), delays);
        assertEquals("CANCELLED", new SessionStore(workspace).recent(20).getFirst().status());
        assertFalse(Thread.currentThread().isInterrupted());
        assertEndpoint(format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 重试提示时取消不会等待或发出新请求(String format) throws Exception {
        configure(format);
        replies.add(brokenSse(": keepalive\n\n"));
        Result result = run(new Input("检查当前项目", "/exit"), true);
        assertEquals(130, result.code, result.err);
        assertEquals(1, requests.size());
        assertTrue(delays.isEmpty());
        assertEquals("CANCELLED", new SessionStore(workspace).recent(20).getFirst().status());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 断线丢弃未完成工具且不重放已经执行的工具(String format) throws Exception {
        configure(format);
        replies.add(completedTool(format));
        replies.add(brokenSse(toolFragment(format, "uncommitted", "must-not-exist.txt")));
        replies.add(answer(format));
        Result result = run(new Input("修改一次", "/exit"), false);
        assertEquals(0, result.code, result.err);
        assertEquals(3, requests.size());
        assertEquals(List.of(1000L), delays);
        assertFallbackRequest(1, 2, format);
        assertEquals("唯一修改", Files.readString(workspace.resolve("kept.txt")));
        assertFalse(Files.exists(workspace.resolve("must-not-exist.txt")));
        assertFalse(requests.getLast().toString().contains("uncommitted"));
        var store = new SessionStore(workspace);
        var transcript = store.load(store.recent(20).getFirst().id()).transcript();
        assertEquals(1, transcript.stream().filter(message -> message.role() == dev.codeharness.core.model.ChatMessage.Role.TOOL).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 配置关闭降级时传输故障不自动重试(String format) throws Exception {
        configure(format);
        Files.writeString(config, Files.readString(config) + "agent:\n  stream-fallback: false\n");
        replies.add(brokenSse(": keepalive\n\n"));
        Result result = run(new Input("检查当前项目", "/exit"), false);
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertTrue(delays.isEmpty());
        assertTrue(result.err.contains("配置已关闭自动降级重试"), result.err);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "responses"})
    void 主动使用非流式时传输故障不误入流式降级(String format) throws Exception {
        configure(format);
        replies.add(brokenJson());
        Result result = run(new Input("检查当前项目", "/exit"), false, "--no-stream");
        assertEquals(1, result.code);
        assertEquals(1, requests.size());
        assertFalse(requests.getFirst().path("stream").asBoolean());
        assertTrue(delays.isEmpty());
        assertTrue(result.err.contains("未收到完整响应"), result.err);
        assertFalse(result.err.contains("正在自动改用"));
    }

    private void configure(String format) throws IOException {
        Files.writeString(config, "provider: openai-compatible\napi-format: " + format
                + "\nmodel: transport-fixture\napi-key: " + KEY + "\napi-key-env: ''\nbase-url: http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1\nweb:\n  enabled: false\npermissions:\n  auto-review: false\n");
    }

    private void respond(HttpExchange exchange) {
        Reply reply = null;
        try {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            int index;
            synchronized (requests) {
                index = requests.size();
                requests.add(request);
                paths.add(exchange.getRequestURI().getPath());
            }
            if (index >= replies.size()) {
                serverErrors.add("出现计划外请求 " + index);
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            reply = replies.get(index);
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.type);
            exchange.getResponseHeaders().set("Connection", "close");
            // 先返回有效响应头和少量正文，避免无响应头断连触发 HttpClient 自身的透明重发。
            exchange.sendResponseHeaders(reply.status, bytes.length + (reply.truncated ? 8192L : 0L));
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            if (reply.holdMillis > 0) Thread.sleep(reply.holdMillis);
        } catch (IOException failure) {
            if (reply == null || !reply.truncated) serverErrors.add(failure.getClass().getSimpleName());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private void assertFallbackRequest(int first, int retry, String format) {
        assertTrue(requests.get(first).path("stream").asBoolean());
        assertFalse(requests.get(retry).path("stream").asBoolean());
        ObjectNode before = requests.get(first).deepCopy(), after = requests.get(retry).deepCopy();
        before.remove("stream");
        after.remove("stream");
        assertEquals(before, after, "重试必须保持模型、工具、消息及格式相同");
        assertEndpoint(format);
    }

    private void assertEndpoint(String format) {
        String endpoint = format.equals("chat") ? "/v1/chat/completions" : "/v1/responses";
        assertTrue(paths.stream().allMatch(endpoint::equals), paths.toString());
        for (JsonNode request : requests) {
            assertTrue(request.path(format.equals("chat") ? "messages" : "input").isArray());
            assertFalse(request.has(format.equals("chat") ? "input" : "messages"));
        }
    }

    private void assertNoKey(Result result) throws IOException {
        assertFalse((result.out + result.err).contains(KEY));
        try (var files = Files.walk(workspace.resolve(".harness"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains(KEY), file.toString());
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"chat,true", "responses,true", "chat,false", "responses,false"})
    void 临时HTTP错误保持协议和用户选定的流模式(String format, boolean streaming) throws Exception {
        configure(format);
        if (!streaming) Files.writeString(config, Files.readString(config) + "agent:\n  stream: false\n");
        replies.add(new Reply(429, "application/json", "{}", false, 0));
        replies.add(new Reply(503, "application/json", "{}", false, 0));
        replies.add(answer(format));
        Result result = run(new Input(), false, "--json", "run", "检查当前项目");
        assertEquals(0, result.code, result.err);
        assertEquals(3, requests.size());
        assertEquals(List.of(1000L, 2000L), delays);
        assertEndpoint(format);
        for (JsonNode request : requests) {
            assertEquals(streaming, request.path("stream").asBoolean());
            assertEquals(requests.getFirst(), request, "保持同一模型、协议、消息和工具");
        }
        assertEquals(3, JSON.readTree(result.out).path("usageReport").path("requests").asInt());
        assertNoKey(result);
    }

    private static Reply brokenSse(String body) { return new Reply("text/event-stream", body, true, 0); }
    private static Reply brokenJson() { return new Reply("application/json", " ", true, 0); }

    private static Reply answer(String format) throws Exception {
        Map<String, Object> payload = format.equals("chat")
                ? Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", ANSWER), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 3))
                : Map.of("id", "response-answer", "status", "completed", "output", List.of(message("answer-item", ANSWER)),
                "usage", Map.of("input_tokens", 11, "output_tokens", 3));
        return new Reply("application/json", JSON.writeValueAsString(payload), false, 0);
    }

    private static String textDelta(String format, String text) throws Exception {
        return format.equals("chat") ? chatEvent(Map.of("content", text), null)
                : event(Map.of("type", "response.output_text.delta", "sequence_number", 0,
                "item_id", "partial-message", "output_index", 0, "content_index", 0, "delta", text));
    }

    private static Reply completedTool(String format) throws Exception {
        String body;
        if (format.equals("chat")) body = chatEvent(chatTool("committed", "kept.txt"), "tool_calls") + "data: [DONE]\n\n";
        else body = event(Map.of("type", "response.completed", "sequence_number", 0, "response", Map.of(
                "id", "tool-response", "status", "completed", "output", List.of(Map.of("type", "function_call",
                "id", "committed-item", "call_id", "committed", "status", "completed", "name", "write_file",
                "arguments", arguments("kept.txt"))))));
        return new Reply("text/event-stream", body, false, 0);
    }

    private static String toolFragment(String format, String id, String destination) throws Exception {
        return format.equals("chat") ? chatEvent(chatTool(id, destination), null)
                : event(Map.of("type", "response.function_call_arguments.delta", "sequence_number", 0,
                "item_id", id + "-item", "output_index", 0, "delta", arguments(destination)));
    }

    private static Map<String, Object> chatTool(String id, String destination) throws Exception {
        return Map.of("tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function",
                Map.of("name", "write_file", "arguments", arguments(destination)))));
    }

    private static String arguments(String destination) throws Exception {
        return JSON.writeValueAsString(Map.of("path", destination, "content", "唯一修改"));
    }

    private static Map<String, Object> message(String id, String text) {
        return Map.of("type", "message", "id", id, "status", "completed", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", text)));
    }

    private static String chatEvent(Map<String, Object> delta, String finish) throws Exception {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        return event(Map.of("choices", List.of(choice)));
    }

    private static String event(Map<String, Object> value) throws Exception {
        return "data: " + JSON.writeValueAsString(value) + "\n\n";
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

    private record Reply(int status, String type, String body, boolean truncated, long holdMillis) {
        Reply(String type, String body, boolean truncated, long holdMillis) { this(200, type, body, truncated, holdMillis); }
    }
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
