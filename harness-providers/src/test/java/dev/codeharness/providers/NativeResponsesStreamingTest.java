package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Responses 原生流只连接 localhost；完成屏障前的函数增量永不变成工具授权。 */
class NativeResponsesStreamingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "responses-stream-fixture-key";
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @AfterEach void stop() { servers.forEach(server -> server.stop(0)); }

    @Test void 文本增量在完成响应之前可见且最终不会重复() throws Exception {
        var deltaReceived = new CountDownLatch(1);
        var allowCompletion = new CountDownLatch(1);
        ChatModel model = streaming(exchange -> {
            startSse(exchange);
            write(exchange, delta("msg", 0, 0, "早到", 1));
            try { assertTrue(allowCompletion.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            write(exchange, completed(List.of(message("msg", "早到完整回复")), 2));
            exchange.close();
        }, Duration.ofSeconds(5));
        var text = new StringBuilder();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> model.complete(request(), value -> { text.append(value); deltaReceived.countDown(); }));
            try {
                assertTrue(deltaReceived.await(2, TimeUnit.SECONDS), "必须在 completed 到达前显示增量");
                assertFalse(pending.isDone());
                assertEquals("早到", text.toString());
            } finally { allowCompletion.countDown(); }
            var response = pending.get(3, TimeUnit.SECONDS);
            assertEquals("早到完整回复", response.text());
            assertEquals("早到", text.toString(), "不能伪造最终文本 delta 或重复已经展示的片段");
            assertEquals(FinishReason.STOP, response.finishReason());
            assertEquals(100, response.usage().inputTokens());
            assertEquals(7, response.usage().outputTokens());
            assertEquals(80, response.usage().cachedInputTokens());
        }
        assertTrue(requests.getFirst().path("stream").asBoolean());
        assertTrue(requests.getFirst().path("input").isArray());
        assertFalse(requests.getFirst().has("messages"));
        assertFalse(requests.getFirst().path("store").asBoolean(true));
    }

    @Test void 完整函数和加密推理按原样回放而不重复增量() throws Exception {
        List<Map<String, Object>> output = List.of(Map.of("type", "reasoning", "id", "rs-1", "summary", List.of(),
                "encrypted_content", "opaque-stream-reasoning"), message("msg", "读取文件"),
                Map.of("type", "function_call", "id", "fc-1", "call_id", "call-1", "name", "read_file", "arguments", "{\"path\":\"A.java\"}", "status", "completed"));
        ChatModel model = streaming(exchange -> {
            startSse(exchange);
            if (requests.size() == 1) {
                write(exchange, delta("msg", 1, 0, "读取文件", 1));
                write(exchange, event(Map.of("type", "response.function_call_arguments.delta", "item_id", "fc-1",
                        "output_index", 2, "delta", "{\"path\":", "sequence_number", 2)));
                write(exchange, completed(output, 3));
            } else write(exchange, completed(List.of(message("next", "完成")), 1));
            exchange.close();
        }, Duration.ofSeconds(5));
        var response = model.complete(request(), ignored -> {});
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("{\"path\":\"A.java\"}", response.toolCalls().getFirst().argumentsJson());
        assertEquals(JSON.valueToTree(output), JSON.readTree(response.providerMetadata().get(OpenAiResponsesProvider.OUTPUT_ITEMS)));
        var history = List.of(ChatMessage.user("修复文件"), ChatMessage.assistantToolCalls(response.text(), response.toolCalls(), response.providerMetadata()),
                ChatMessage.toolResult(response.toolCalls().getFirst(), "file content"));
        model.complete(new ChatRequest(history, List.of(), 100), ignored -> {});
        JsonNode input = requests.getLast().path("input");
        assertEquals(5, input.size());
        for (int index = 0; index < output.size(); index++) assertEquals(JSON.valueToTree(output.get(index)), input.get(index + 1));
        assertEquals("function_call_output", input.get(4).path("type").asText());
        assertEquals("call-1", input.get(4).path("call_id").asText());
    }

    @Test void 没有completed即使收到完整工具done仍失败() throws Exception {
        for (String body : List.of(
                event(Map.of("type", "response.output_item.done", "item", Map.of("type", "function_call", "call_id", "call-1",
                        "name", "write_file", "arguments", "{}", "status", "completed"))),
                event(Map.of("type", "response.function_call_arguments.done", "item_id", "fc-1", "arguments", "{}")),
                "data: [DONE]\n\n", delta("msg", 0, 0, "部分", 1))) {
            ChatModel model = body("text/event-stream", body);
            assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        }
    }

    @Test void 失败与不完整终止不会暴露工具并给出脱敏原因() throws Exception {
        for (String type : List.of("response.failed", "response.incomplete")) {
            String response = event(Map.of("type", type, "response", Map.of("status", type.substring("response.".length()),
                    "error", Map.of("message", "upstream unavailable " + SECRET), "output", List.of(
                    Map.of("type", "function_call", "call_id", "unsafe", "name", "write_file", "arguments", "{}")))));
            var model = body("text/event-stream", response);
            ProviderException error = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
            assertTrue(error.getMessage().contains("upstream unavailable"));
            assertFalse(error.toString().contains(SECRET));
            assertNull(error.getCause());
        }
        var model = body("text/event-stream", event(Map.of("type", "error", "message", "invalid request " + SECRET, "code", "invalid_request_error")));
        ProviderException error = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        assertTrue(error.getMessage().contains("invalid request"));
        assertFalse(error.getMessage().contains(SECRET));
    }

    @Test void JSON兼容响应保持完整校验且不伪造流式事件() throws Exception {
        var emitted = new ArrayList<String>();
        ChatModel model = body("application/json", JSON.writeValueAsString(envelope(List.of(message("msg", "普通JSON")))));
        var response = model.complete(request(), emitted::add);
        assertEquals("普通JSON", response.text());
        assertTrue(emitted.isEmpty());
        for (String status : List.of("incomplete", "failed", "in_progress")) {
            ChatModel invalid = body("application/json", JSON.writeValueAsString(Map.of("status", status, "output", List.of())));
            assertThrows(ProviderException.class, () -> invalid.complete(request(), emitted::add));
        }
    }

    @Test void 增量身份内容序号或完成数据矛盾均拒绝() throws Exception {
        String finalEvent = completed(List.of(message("msg", "correct")), 5);
        for (String body : List.of(
                delta("msg", 0, 0, "wrong", 1) + finalEvent,
                delta("different", 0, 0, "correct", 1) + finalEvent,
                delta("msg", 0, 1, "correct", 1) + finalEvent,
                delta("msg", 0, 0, "cor", 1) + delta("msg", 0, 0, "rect", 1) + finalEvent,
                "data: {\"type\":\"response.completed\",\"response\":{},\"response\":{}}\n\n",
                event(Map.of("type", "response.created", "response", Map.of("id", "earlier"))) + finalEvent,
                event(Map.of("type", "response.completed", "response", Map.of("status", "incomplete", "output", List.of()))),
                event(Map.of("choices", List.of())))) {
            var model = body("text/event-stream", body);
            assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        }
    }

    @Test void 仍调用既有完整输出验证器阻止无加密推理及非法函数() throws Exception {
        for (List<Map<String, Object>> output : List.of(
                List.of(Map.<String, Object>of("type", "reasoning", "id", "rs", "summary", List.of()), message("msg", "done")),
                List.of(Map.<String, Object>of("type", "function_call", "call_id", "one", "name", "write_file", "arguments", "[]")),
                List.of(Map.<String, Object>of("type", "function_call", "call_id", "one", "name", "write_file", "arguments", "{}", "status", "in_progress")))) {
            var model = body("text/event-stream", completed(output, 1));
            assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        }
    }

    @Test void 事件分隔不完整及超大事件被传输边界拦截() throws Exception {
        String full = completed(List.of(message("msg", "done")), 1);
        var truncated = body("text/event-stream", full.substring(0, full.length() - 2));
        assertThrows(ProviderException.class, () -> truncated.complete(request(), ignored -> {}));
        var oversized = body("text/event-stream", "data: " + "x".repeat(NativeStreamTransport.MAX_EVENT_CHARS + 1) + "\n\n");
        assertThrows(ProviderException.class, () -> oversized.complete(request(), ignored -> {}));
    }

    @Test void HTTP错误包含脱敏详情且400保留降级信号() throws Exception {
        var model = streaming(exchange -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("error", Map.of("message", "stream is unsupported " + SECRET, "param", "stream")));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }, Duration.ofSeconds(3));
        ProviderException failure = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        assertEquals(400, failure.statusCode());
        assertTrue(failure.streamFallbackSuggested());
        assertTrue(failure.getMessage().contains("stream is unsupported"));
        assertFalse(failure.getMessage().contains(SECRET));
    }

    @Test void 流式总超时和调用中断都能及时退出() throws Exception {
        var release = new CountDownLatch(1);
        var connected = new CountDownLatch(1);
        var model = streaming(exchange -> {
            startSse(exchange);
            write(exchange, ": keepalive\n\n");
            connected.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        }, Duration.ofMillis(300));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                ProviderException failure = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
                assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
                assertTrue(failure.streamFallbackSuggested());
            });
            assertTrue(connected.await(1, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                var interrupted = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
                assertFalse(interrupted.streamFallbackSuggested());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        } finally { release.countDown(); }
    }

    private ChatModel body(String contentType, String body) throws Exception {
        return streaming(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }, Duration.ofSeconds(5));
    }

    private ChatModel streaming(com.sun.net.httpserver.HttpHandler handler, Duration timeout) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        server.createContext("/v1/responses", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            assertEquals("Bearer " + SECRET, exchange.getRequestHeaders().getFirst("Authorization"));
            handler.handle(exchange);
        });
        server.start();
        return new OpenAiResponsesProvider().create(new ProviderConfig("openai-responses", "test",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), SECRET, timeout, Map.of()));
    }

    private static ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("修复文件")), List.of(), 100); }
    private static void startSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }
    private static void write(HttpExchange exchange, String value) throws IOException {
        exchange.getResponseBody().write(value.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
    private static String event(Map<String, Object> fields) throws IOException { return "data: " + JSON.writeValueAsString(fields) + "\n\n"; }
    private static String delta(String id, int output, int content, String text, int sequence) throws IOException {
        return event(Map.of("type", "response.output_text.delta", "item_id", id, "output_index", output,
                "content_index", content, "delta", text, "sequence_number", sequence));
    }
    private static String completed(List<Map<String, Object>> output, int sequence) throws IOException {
        return event(Map.of("type", "response.completed", "sequence_number", sequence, "response", envelope(output)));
    }
    private static Map<String, Object> envelope(List<Map<String, Object>> output) {
        return Map.of("id", "response-1", "status", "completed", "output", output,
                "usage", Map.of("input_tokens", 100, "output_tokens", 7, "input_tokens_details", Map.of("cached_tokens", 80)));
    }
    private static Map<String, Object> message(String id, String text) {
        return Map.of("type", "message", "id", id, "role", "assistant", "status", "completed",
                "content", List.of(Map.of("type", "output_text", "text", text)));
    }
}
