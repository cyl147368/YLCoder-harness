package dev.codeharness.providers;

import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpExchangeTraceTest {
    private static final String CHAT = "{\"id\":\"wire-id\",\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2}}";
    private static ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("检查报文")), List.of(), 200); }
    static final class Recording implements HttpExchangeTrace.Listener {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> responses = new CopyOnWriteArrayList<>();
        final List<Integer> statuses = new CopyOnWriteArrayList<>();
        final List<String> states = new CopyOnWriteArrayList<>();
        final List<Boolean> truncated = new CopyOnWriteArrayList<>();
        final List<String> endpoints = new CopyOnWriteArrayList<>();
        public void request(int n, String method, String endpoint, String body) { requests.add(body); endpoints.add(endpoint); }
        public void response(int n, int status, String type, String body, boolean clipped, String state) {
            responses.add(body); statuses.add(status); states.add(state); truncated.add(clipped);
        }
    }

    @ParameterizedTest(name = "{0}：捕获正文与服务端真实收发完全相同")
    @ValueSource(strings = {"openai-compatible", "openai-responses", "anthropic", "gemini", "azure-openai", "langchain4j-openai"})
    void actualWireBodiesMatchServer(String provider) throws Exception {
        String response = switch (provider) {
            case "openai-responses" -> "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"msg1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好\"}]}]}";
            case "anthropic" -> "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],\"stop_reason\":\"end_turn\"}";
            case "gemini" -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你好\"}]},\"finishReason\":\"STOP\"}]}";
            default -> CHAT;
        };
        var received = new AtomicReference<String>();
        HttpServer server = serve(response, "application/json", 200, received);
        try {
            var record = new Recording();
            var config = new ProviderConfig(provider, "m", URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-secret", Duration.ofSeconds(3), Map.of());
            try (var scope = HttpExchangeTrace.observe(record)) {
                assertEquals("你好", new ProviderRegistry().create(config).complete(request()).text());
                assertTrue(scope.observed());
            }
            assertEquals(List.of(received.get()), record.requests);
            assertEquals(List.of(response), record.responses);
            assertEquals(List.of(200), record.statuses);
            assertFalse(record.endpoints.toString().contains("?"));
            assertFalse(record.endpoints.toString().contains("fixture-secret"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest(name = "{0}：流式正文包含事件头、用量和结束标记")
    @ValueSource(strings = {"openai-compatible", "langchain4j-openai"})
    void capturesSseBeforeParsing(String provider) throws Exception {
        String response = ":心跳\r\nevent: message\r\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}\r\n\r\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\r\n\r\n"
                + "data: [DONE]\r\n\r\n";
        var input = new AtomicReference<String>();
        HttpServer server = serve(response, "text/event-stream", 200, input);
        try {
            var record = new Recording();
            try (var scope = HttpExchangeTrace.observe(record)) {
                var model = new ProviderRegistry().create(new ProviderConfig(provider, "m", URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-key"));
                assertEquals("你好", model.complete(request(), delta -> {}).text());
            }
            assertEquals(List.of(input.get()), record.requests);
            assertEquals(List.of(response), record.responses);
            assertTrue(AbstractHttpChatModel.JSON.readTree(record.requests.getFirst()).path("stream").asBoolean());
        } finally { server.stop(0); }
    }

    @Test void capturesResponsesSseIncludingCompletionFields() throws Exception {
        String finalBody = "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"msg1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好\"}]}]}";
        String body = ":心跳\r\nevent: response.completed\r\ndata: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":" + finalBody + "}\r\n\r\n";
        var input = new AtomicReference<String>();
        HttpServer server = serve(body, "text/event-stream", 200, input);
        try {
            var record = new Recording();
            try (var scope = HttpExchangeTrace.observe(record)) {
                var model = new ProviderRegistry().create(new ProviderConfig("openai-responses", "m", URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-key"));
                assertEquals("你好", model.complete(request(), delta -> {}).text());
            }
            assertEquals(List.of(input.get()), record.requests);
            assertEquals(List.of(body), record.responses);
        } finally { server.stop(0); }
    }

    @ParameterizedTest(name = "{0}：HTTP 错误的原始详情也会留存")
    @ValueSource(strings = {"openai-compatible", "langchain4j-openai"})
    void capturesHttpErrorForStreamingAndPlain(String provider) throws Exception {
        String body = "{\"error\":{\"message\":\"fixture-invalid-model\",\"nested\":{\"request_id\":\"original-id\"}}}";
        HttpServer server = serve(body, "application/json", 400, new AtomicReference<>());
        try {
            var record = new Recording();
            var model = new ProviderRegistry().create(new ProviderConfig(provider, "m", URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-key"));
            try (var scope = HttpExchangeTrace.observe(record)) {
                assertThrows(ProviderException.class, () -> model.complete(request(), delta -> {}));
                assertThrows(ProviderException.class, () -> model.complete(request()));
            }
            assertEquals(List.of(body, body), record.responses);
            assertEquals(List.of(400, 400), record.statuses);
        } finally { server.stop(0); }
    }

    @Test void captureIsBoundedAndNestedScopesDoNotMix() throws Exception {
        var first = new Recording();
        var second = new Recording();
        HttpExchangeTrace.Exchange late;
        try (var outer = HttpExchangeTrace.observe(first)) {
            late = HttpExchangeTrace.start("POST", URI.create("http://u:p@localhost/path?key=secret"), "first");
            late.accept("片段");
            try (var nested = HttpExchangeTrace.observe(second)) {
                var exchange = HttpExchangeTrace.start("POST", URI.create("http://localhost/path"), "second");
                exchange.accept("x".repeat(HttpExchangeTrace.MAX_CAPTURE_BYTES + 5));
                exchange.finish("complete");
            }
        }
        late.accept("迟到正文不应写入"); late.finish("late");
        assertEquals(List.of("片段"), first.responses);
        assertEquals(List.of("http://localhost/path"), first.endpoints);
        assertTrue(first.states.getFirst().contains("未完成"));
        assertEquals(HttpExchangeTrace.MAX_CAPTURE_BYTES, second.responses.getFirst().length());
        assertEquals(List.of(true), second.truncated);
    }

    @Test void observerFailureNeverChangesTransportResult() throws Exception {
        HttpServer server = serve(CHAT, "application/json", 200, new AtomicReference<>());
        try (var scope = HttpExchangeTrace.observe(new HttpExchangeTrace.Listener() {
            public void request(int n, String method, String endpoint, String body) { throw new IllegalStateException("日志失效"); }
            public void response(int n, int status, String type, String body, boolean clipped, String state) { throw new IllegalStateException("日志失效"); }
        })) {
            var model = new OpenAiCompatibleProvider().create(new ProviderConfig("openai-compatible", "m", URI.create("http://localhost:" + server.getAddress().getPort()), null));
            assertEquals("你好", model.complete(request()).text());
        } finally { server.stop(0); }
    }

    private static HttpServer serve(String response, String type, int status, AtomicReference<String> received) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
