package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 只提供 Responses 的 localhost 厂商也必须完成主任务、审核和恢复，不得偷偷请求 Chat。 */
class ResponsesApprovalCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK = "检查日期；api-key=conversation-fixture-secret";
    private static final String FOLLOWUP = "继续根据刚才的结果回答";
    private static final String KEY = "responses-fixture-key";
    private static final String FINAL_TEXT = "Responses 主任务已完成。";
    private static final String ASSISTANT_PROSE = "不能作为审核授权的助手自由文本";
    private static final String REVIEW_REASON = "分类器理由不能写入主会话";
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"openai-compatible,responses", "openai,responses", "openai-responses,legacy"})
    void 配置或旧provider使主模型及审核模型都请求Responses(String provider, String format) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            Path config = config(endpoint, provider, format.equals("legacy") ? null : format, null);
            Result result = run(config);
            assertRoundTrip(endpoint, result);
            SessionStore.Session saved = new SessionStore(temporary).load(JSON.readTree(result.out()).path("session").asText());
            assertEquals("openai-responses", saved.provider());
        }
    }

    @Test void CLI格式覆盖Chat配置且审核继承实际主协议() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            Path config = config(endpoint, "openai-compatible", "chat", null);
            byte[] original = Files.readAllBytes(config);
            Result result = run(config, "--api-format", "responses");
            assertRoundTrip(endpoint, result);
            assertArrayEquals(original, Files.readAllBytes(config), "命令行协议选择不能改写用户配置");
        }
    }

    @Test void 恢复已保存Responses会话仍回放历史及实际协议() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            Path config = config(endpoint, "openai-compatible", null, null);
            Result first = run(config, "--api-format", "responses");
            assertRoundTrip(endpoint, first);
            String session = JSON.readTree(first.out()).path("session").asText();
            var input = List.of("/resume " + session, FOLLOWUP, "/exit").iterator();
            Result resumed = execute(config, new TerminalInput() {
                public String readLine() { return input.hasNext() ? input.next() : null; }
                public boolean isTerminal() { return false; }
            }, List.of());
            assertEquals(0, resumed.code(), resumed.err());
            assertTrue(endpoint.failures.isEmpty(), endpoint.failures.toString());
            assertEquals(0, endpoint.otherRequests.get(), "恢复后不能回到不支持的 Chat 端点");
            assertEquals(3, endpoint.main.size());
            assertEquals(1, endpoint.reviews.size(), "恢复后没有新工具就不应再次审核");
            JsonNode last = endpoint.main.getLast();
            assertEquals(List.of(TASK, FOLLOWUP), userContents(last.path("input")));
            assertTrue(last.path("input").toString().contains(FINAL_TEXT));
            assertNotNull(item(last.path("input"), "function_call"));
            assertNotNull(item(last.path("input"), "function_call_output"));
            SessionStore.Session saved = new SessionStore(temporary).load(session);
            assertEquals("openai-responses", saved.provider());
            assertEquals(List.of(TASK, FOLLOWUP), saved.transcript().stream()
                    .filter(message -> message.role() == dev.codeharness.core.model.ChatMessage.Role.USER)
                    .map(dev.codeharness.core.model.ChatMessage::content).toList());
        }
    }

    @Test void 主Chat可独立选择Responses审核且继承同端点凭据() throws Exception {
        try (Endpoint endpoint = new Endpoint(true)) {
            Path config = config(endpoint, "openai-compatible", null, "responses");
            Result result = run(config);
            assertEquals(0, result.code(), result.err());
            assertTrue(endpoint.failures.isEmpty(), endpoint.failures.toString());
            assertEquals(0, endpoint.otherRequests.get());
            assertEquals(2, endpoint.chat.size());
            assertEquals(0, endpoint.main.size());
            assertEquals(1, endpoint.reviews.size());
            assertEquals(List.of("Bearer " + KEY), endpoint.reviewCredentials);
            assertFalse(endpoint.reviews.getFirst().has("messages"));
            assertTrue(endpoint.reviews.getFirst().path("input").isArray());
            assertTrue(endpoint.chat.getLast().path("messages").toString().contains("退出码：0"));
            JsonNode review = JSON.readTree(result.out()).path("review");
            assertEquals(1, review.path("approved").asInt());
            assertEquals(0, review.path("manual").asInt());
        }
    }

    @Test void 无效格式和不支持的协议组合在发请求前失败() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            Path config = config(endpoint, "openai-compatible", null, null);
            for (String[] options : new String[][]{
                    {"--api-format", "invalid-format-fixture"},
                    {"--provider", "anthropic", "--api-format", "responses"}}) {
                Result result = run(config, options);
                assertNotEquals(0, result.code());
                assertTrue(result.err().contains("api-format"), result.err());
                assertFalse(result.err().contains("invalid-format-fixture"));
            }
            assertEquals(0, endpoint.otherRequests.get());
            assertTrue(endpoint.main.isEmpty());
            assertTrue(endpoint.reviews.isEmpty());
        }
    }

    private void assertRoundTrip(Endpoint endpoint, Result result) throws Exception {
        assertEquals(0, result.code(), result.err());
        assertTrue(endpoint.failures.isEmpty(), endpoint.failures.toString());
        assertEquals(0, endpoint.otherRequests.get(), "只提供 /responses 的端点不能收到任何 Chat 请求");
        assertEquals(2, endpoint.main.size());
        assertEquals(1, endpoint.reviews.size());
        assertEquals(List.of("Bearer " + KEY), endpoint.reviewCredentials);
        for (JsonNode request : endpoint.main) {
            assertTrue(request.path("input").isArray());
            assertFalse(request.has("messages"));
            assertFalse(request.path("stream").asBoolean());
            assertEquals("responses-main-fixture", request.path("model").asText());
            assertEquals(List.of(TASK), userContents(request.path("input")));
        }
        JsonNode call = item(endpoint.main.getLast().path("input"), "function_call");
        JsonNode output = item(endpoint.main.getLast().path("input"), "function_call_output");
        assertNotNull(call);
        assertNotNull(output);
        assertEquals("date-call", call.path("call_id").asText());
        assertEquals("date-call", output.path("call_id").asText());
        assertTrue(output.path("output").asText().startsWith("退出码：0\n"), output.toString());
        assertFalse(output.path("output").asText().contains("未获批准"));
        JsonNode request = endpoint.reviews.getFirst();
        assertEquals("responses-review-fixture", request.path("model").asText());
        assertFalse(request.has("messages"));
        assertTrue(request.path("input").isArray());
        assertFalse(request.path("stream").asBoolean());
        assertTrue(request.path("tools").isEmpty());
        assertEquals(192, request.path("max_output_tokens").asInt());
        JsonNode context = JSON.readTree(userContents(request.path("input")).getFirst());
        assertEquals("date", context.path("command").asText());
        assertTrue(context.path("context").toString().contains("检查日期"));
        assertTrue(context.path("context").toString().contains("[已隐藏凭据]"));
        assertFalse(context.toString().contains("conversation-fixture-secret"));
        assertFalse(context.toString().contains(ASSISTANT_PROSE));
        JsonNode report = JSON.readTree(result.out());
        assertEquals("COMPLETED", report.path("status").asText());
        assertEquals(2, report.path("iterations").asInt());
        assertTrue(report.toString().contains(FINAL_TEXT));
        assertEquals(1, report.path("review").path("requests").asInt());
        assertEquals(1, report.path("review").path("approved").asInt());
        assertEquals(0, report.path("review").path("localApprovals").asInt());
        assertEquals(0, report.path("review").path("manual").asInt());
        assertEquals(11, report.path("review").path("usage").path("inputTokens").asInt());
        assertEquals(4, report.path("review").path("usage").path("outputTokens").asInt());
        assertEquals(26, report.path("agentUsage").path("inputTokens").asInt());
        assertEquals(6, report.path("agentUsage").path("outputTokens").asInt());
        assertEquals(37, report.path("usage").path("inputTokens").asInt());
        assertEquals(10, report.path("usage").path("outputTokens").asInt());
        assertFalse((result.out() + result.err()).contains(KEY));
        assertFalse((result.out() + result.err()).contains(REVIEW_REASON));
        String log = Files.readString(Path.of(report.path("traceFile").asText()));
        assertEquals(3, log.split("## 模型请求 #", -1).length - 1, "两次主请求加一次审核，记录不能额外请求模型");
        assertEquals(2, log.split("- 来源：agent", -1).length - 1);
        assertEquals(1, log.split("- 来源：reviewer", -1).length - 1);
        assertTrue(log.contains("responses-main-fixture") && log.contains("responses-review-fixture"), log);
        assertTrue(log.contains("HTTP 响应状态 #2") && log.contains(REVIEW_REASON), log);
        assertEquals(3, log.split("## HTTP 请求报文 #", -1).length - 1);
        assertEquals(3, log.split("## HTTP 响应状态 #", -1).length - 1);
        assertTrue(log.contains("工具开始") && log.contains("工具完成"), log);
        assertFalse(log.contains(KEY), "配置凭据不能写进日志");
        assertFalse(log.contains("conversation-fixture-secret"), "用户内容中的凭据也应遮盖");
    }

    private Path config(Endpoint endpoint, String provider, String format, String reviewerFormat) throws IOException {
        String yaml = "provider: " + provider + "\nmodel: responses-main-fixture\napi-key: " + KEY + "\nbase-url: "
                + "http://127.0.0.1:" + endpoint.server.getAddress().getPort() + "/v1\n"
                + (format == null ? "" : "api-format: " + format + "\n")
                + "web:\n  enabled: false\npermissions:\n  mode: review\n  reviewer:\n    model: responses-review-fixture\n"
                + (reviewerFormat == null ? "" : "    api-format: " + reviewerFormat + "\n");
        return Files.writeString(temporary.resolve("responses-fixture.yaml"), yaml);
    }

    private Result run(Path config, String... options) {
        var flags = new ArrayList<>(List.of(options));
        flags.addAll(List.of("--json", "run", TASK));
        return execute(config, new TerminalInput() {
            public String readLine() { throw new AssertionError("已审核的日期查询不应要求人工确认"); }
            public boolean isTerminal() { return false; }
        }, flags);
    }

    private Result execute(Path config, TerminalInput input, List<String> options) {
        var arguments = new ArrayList<>(List.of("--workspace", temporary.toString(), "--config", config.toString(),
                "--no-stream", "--quiet", "--no-color"));
        arguments.addAll(options);
        var cli = new CommandLine(new HarnessCli(input));
        var out = new StringWriter();
        var err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        int code = cli.execute(arguments.toArray(String[]::new));
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}

    private static JsonNode item(JsonNode input, String type) {
        for (JsonNode node : input) if (node.path("type").asText().equals(type)) return node;
        return null;
    }

    private static List<String> userContents(JsonNode input) {
        List<String> users = new ArrayList<>();
        for (JsonNode node : input) if (node.path("role").asText().equals("user")) users.add(node.path("content").asText());
        return users;
    }

    private static Map<String, Object> message(String id, String text) {
        return Map.of("type", "message", "id", id, "status", "completed", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", text)));
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> main = new CopyOnWriteArrayList<>(), reviews = new CopyOnWriteArrayList<>(), chat = new CopyOnWriteArrayList<>();
        final List<String> reviewCredentials = new CopyOnWriteArrayList<>(), failures = new CopyOnWriteArrayList<>();
        final AtomicInteger otherRequests = new AtomicInteger();

        Endpoint() throws IOException { this(false); }

        Endpoint(boolean acceptMainChat) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (!path.equals("/v1/responses") && !(acceptMainChat && path.equals("/v1/chat/completions"))) {
                        otherRequests.incrementAndGet();
                        reply(exchange, 404, Map.of("error", Map.of("message", "only Responses endpoint is supported")));
                        return;
                    }
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    assertEquals("Bearer " + KEY, exchange.getRequestHeaders().getFirst("Authorization"));
                    boolean reviewer = request.path("model").asText().equals("responses-review-fixture");
                    if (path.equals("/v1/chat/completions")) {
                        assertFalse(reviewer, "审核独立选择 Responses，不能走 Chat");
                        chat.add(request);
                        Map<String, Object> content = chat.size() == 1
                                ? Map.of("role", "assistant", "content", ASSISTANT_PROSE, "tool_calls", List.of(Map.of(
                                "id", "date-call", "type", "function", "function", Map.of("name", "run_command", "arguments", "{\"command\":\"date\"}"))))
                                : Map.of("role", "assistant", "content", FINAL_TEXT);
                        reply(exchange, 200, Map.of("choices", List.of(Map.of("message", content, "finish_reason", chat.size() == 1 ? "tool_calls" : "stop")),
                                "usage", Map.of("prompt_tokens", 13, "completion_tokens", 3)));
                        return;
                    }
                    assertTrue(request.path("input").isArray());
                    assertFalse(request.has("messages"));
                    List<Map<String, Object>> output;
                    if (reviewer) {
                        reviews.add(request);
                        reviewCredentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        output = List.of(message("review-message", "{\"decision\":\"approve\",\"reason\":\"" + REVIEW_REASON + "\"}"));
                    } else {
                        main.add(request);
                        if (main.size() == 1) output = List.of(message("main-comment", ASSISTANT_PROSE), Map.of("type", "function_call",
                                "id", "function-item", "call_id", "date-call", "status", "completed", "name", "run_command", "arguments", "{\"command\":\"date\"}"));
                        else output = List.of(message("main-final-" + main.size(), FINAL_TEXT));
                    }
                    reply(exchange, 200, Map.of("id", "response-fixture", "status", "completed", "output", output,
                            "usage", Map.of("input_tokens", reviewer ? 11 : 13, "output_tokens", reviewer ? 4 : 3,
                                    "input_tokens_details", Map.of("cached_tokens", reviewer ? 2 : 5))));
                } catch (Throwable error) {
                    failures.add(error.toString());
                    reply(exchange, 500, Map.of("error", Map.of("message", "local fixture assertion failed")));
                }
            });
            server.start();
        }

        public void close() { server.stop(0); }
    }

    private static void reply(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
