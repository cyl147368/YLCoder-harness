package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativeChatStreamingTest {
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @AfterEach void stop() {
        release.countDown();
        if (server != null) server.stop(0);
    }

    @Test void emitsTextBeforeFinishAndCollectsUsageTail() throws Exception {
        CountDownLatch visible = new CountDownLatch(1);
        AtomicReference<JsonNode> body = new AtomicReference<>();
        start(exchange -> {
            body.set(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            open(exchange);
            event(exchange, chunk("{\"role\":\"assistant\",\"content\":\"Hel\"}", null));
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("finish 尚未释放");
            event(exchange, chunk("{\"content\":\"lo\"}", "stop"));
            event(exchange, "{\"choices\":[],\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":5}}}");
            event(exchange, "[DONE]");
        });
        var text = new CopyOnWriteArrayList<String>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            ChatModel model = model("openai", Duration.ofSeconds(5), Map.of());
            Future<ChatResponse> pending = executor.submit(() -> model.complete(request(), delta -> { text.add(delta); visible.countDown(); }));
            assertTrue(visible.await(3, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            release.countDown();
            var result = pending.get(5, TimeUnit.SECONDS);
            assertEquals("Hello", String.join("", text));
            assertEquals("Hello", result.text());
            assertEquals(FinishReason.STOP, result.finishReason());
            assertEquals(new TokenUsage(17, 2, 5), result.usage());
            assertTrue(body.get().path("stream").asBoolean());
            assertTrue(body.get().path("stream_options").path("include_usage").asBoolean());
            assertNull(serverFailure.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void parallelFragmentedCallsWaitForDoneBeforeAgentDispatch() throws Exception {
        AtomicInteger requests = new AtomicInteger(), mutations = new AtomicInteger();
        CountDownLatch finishedMarker = new CountDownLatch(1);
        List<String> arguments = new CopyOnWriteArrayList<>();
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 2) { json(exchange, completedJson()); return; }
            open(exchange);
            event(exchange, chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{\\\"value\\\":\"}},{\"index\":1,\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{\\\"value\\\":\"}}]}", null));
            event(exchange, chunk("{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"2}\"}},{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]}", "tool_calls"));
            finishedMarker.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("DONE 尚未释放");
            event(exchange, "[DONE]");
        });
        Tool sentinel = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("mutate", "Record mutation", Map.of("type", "object")); }
            @Override public String execute(String args) { arguments.add(args); mutations.incrementAndGet(); return "recorded"; }
        };
        var agent = new CodeAgent(model("openai-compatible", Duration.ofSeconds(5), Map.of()), new ToolRegistry(List.of(sentinel)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> agent.run("mutate twice", AgentRunOptions.defaults()));
            assertTrue(finishedMarker.await(3, TimeUnit.SECONDS));
            assertEquals(0, mutations.get());
            assertFalse(pending.isDone());
            release.countDown();
            var result = pending.get(5, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.COMPLETED, result.status(), result.error());
            assertEquals(2, mutations.get());
            assertEquals(List.of("{\"value\":1}", "{\"value\":2}"), arguments);
            assertEquals(List.of("a", "b"), result.transcript().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).map(ChatMessage::toolCallId).toList());
            assertNull(serverFailure.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void jsonGatewayFallbackAndProtocolOptionsRemainCompatible() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        start(exchange -> { bodies.add(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes())); json(exchange, completedJson()); });
        for (String provider : List.of("openai-compatible", "ollama", "openai", "azure-openai")) {
            var deltas = new ArrayList<String>();
            assertEquals("done", model(provider, Duration.ofSeconds(3), Map.of()).complete(request(), deltas::add).text());
            assertTrue(deltas.isEmpty());
            var body = bodies.getLast();
            assertTrue(body.path("stream").asBoolean());
            assertEquals(List.of("openai", "azure-openai").contains(provider), body.has("stream_options"));
        }
    }

    @Test void truncatedAndFilteredStreamsSuppressApparentlyCompleteCalls() throws Exception {
        AtomicReference<String> finish = new AtomicReference<>("length");
        start(exchange -> { open(exchange); event(exchange, callChunk()); event(exchange, chunk("{}", finish.get())); event(exchange, "[DONE]"); });
        for (String reason : List.of("length", "content_filter")) {
            finish.set(reason);
            var response = model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), delta -> {});
            assertTrue(response.toolCalls().isEmpty());
            assertEquals(reason.equals("length") ? FinishReason.LENGTH : FinishReason.CONTENT_FILTER, response.finishReason());
        }
    }

    @Test void malformedAndIncompleteStreamsFailWithSanitizedErrors() throws Exception {
        AtomicReference<List<String>> events = new AtomicReference<>();
        start(exchange -> { open(exchange); for (String event : events.get()) event(exchange, event); });
        List<List<String>> cases = List.of(
                List.of(chunk("{\"content\":\"secret-stream-content\"}", null), "[DONE]"),
                List.of(chunk("{}", "stop")),
                List.of(callChunk(), chunk("{}", "tool_calls")),
                List.of(chunk("{}", "invented-secret-marker"), "[DONE]"),
                List.of("{\"error\":{\"message\":\"secret-server-error\"}}"),
                List.of("secret-malformed-json", "[DONE]"),
                List.of("{\"choices\":\"secret-invalid-shape\"}", "[DONE]"),
                List.of(chunk("{\"function_call\":{\"name\":\"old_api\"}}", "stop"), "[DONE]"),
                List.of(chunk("{\"tool_calls\":[{\"index\":-1,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{}\"}}]}", "tool_calls"), "[DONE]"),
                List.of(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"[]\"}}]}", "tool_calls"), "[DONE]"),
                List.of(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{}\"}}]}", "tool_calls"), "[DONE]"),
                List.of(chunk("{\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{}\"}}]}", "tool_calls"), "[DONE]"),
                List.of(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{} {}\"}}]}", "tool_calls"), "[DONE]"),
                List.of(callChunk(), chunk("{}", "tool_calls"), "{\"choices\":[],\"usage\":{\"prompt_tokens\":-1}}", "[DONE]"),
                List.of(chunk("{}", "stop"), "{\"choices\":[],\"usage\":{\"completion_tokens\":\"secret-number\"}}", "[DONE]"));
        for (List<String> fixture : cases) {
            events.set(fixture);
            var failure = assertThrows(ProviderException.class, () -> model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), delta -> {}), fixture.toString());
            assertFalse(failure.toString().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test void unfinishedStreamNeverExecutesAgentMutation() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        start(exchange -> { open(exchange); event(exchange, callChunk()); event(exchange, chunk("{}", "tool_calls")); });
        Tool tool = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("mutate", "sentinel", Map.of("type", "object")); }
            @Override public String execute(String args) { effects.incrementAndGet(); return "changed"; }
        };
        var result = new CodeAgent(model("openai-compatible", Duration.ofSeconds(3), Map.of()), new ToolRegistry(List.of(tool)))
                .run("mutate", AgentRunOptions.defaults());
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(0, effects.get());
        assertTrue(result.transcript().stream().allMatch(m -> m.toolCalls().isEmpty()));
    }

    @Test void boundsOversizedSseEventAndJsonBody() throws Exception {
        AtomicReference<Boolean> sse = new AtomicReference<>(true);
        start(exchange -> {
            if (sse.get()) { open(exchange); event(exchange, "x".repeat(1024 * 1024 + 1)); }
            else json(exchange, " ".repeat(8 * 1024 * 1024 + 1) + completedJson());
        });
        for (boolean streaming : List.of(true, false)) {
            sse.set(streaming);
            assertThrows(ProviderException.class, () -> model("openai-compatible", Duration.ofSeconds(5), Map.of()).complete(request(), delta -> {}));
        }
    }

    @Test void stalledBodyHasTotalDeadlineAfterHeaders() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        start(exchange -> { open(exchange); headers.countDown(); release.await(5, TimeUnit.SECONDS); });
        ChatModel model = model("openai-compatible", Duration.ofMillis(350), Map.of());
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var error = assertThrows(ProviderException.class, () -> model.complete(request(), delta -> {}));
            assertNull(error.getCause());
        });
        assertEquals(0, headers.getCount());
    }

    @Test void cumulativeSseBytesAreBoundedEvenForIgnoredComments() throws Exception {
        start(exchange -> {
            open(exchange);
            byte[] comment = (":" + "x".repeat(64 * 1024) + "\n").getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 129; i++) exchange.getResponseBody().write(comment);
            event(exchange, chunk("{\"content\":\"done\"}", "stop")); event(exchange, "[DONE]");
        });
        assertThrows(ProviderException.class, () -> model("openai-compatible", Duration.ofSeconds(5), Map.of()).complete(request(), delta -> {}));
    }

    @Test void callerInterruptionEndsWaitAndPreservesFlag() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        start(exchange -> { open(exchange); headers.countDown(); release.await(5, TimeUnit.SECONDS); });
        var error = new AtomicReference<Throwable>();
        var interrupted = new AtomicReference<Boolean>();
        ChatModel model = model("openai-compatible", Duration.ofSeconds(10), Map.of());
        Thread caller = new Thread(() -> {
            try { model.complete(request(), delta -> {}); }
            catch (Throwable failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            assertTrue(headers.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertInstanceOf(ProviderException.class, error.get());
            assertEquals(Boolean.TRUE, interrupted.get());
            assertNull(error.get().getCause());
        } finally { release.countDown(); caller.interrupt(); caller.join(2_000); }
    }

    @Test void redirectIsNotFollowedAndHttpErrorsAreRedacted() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/capture", exchange -> { captures.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        destination.start();
        AtomicInteger status = new AtomicInteger(307);
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/capture");
            byte[] bytes = "secret-http-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes);
        });
        try {
            for (int code : List.of(307, 401, 503)) {
                status.set(code);
                var failure = assertThrows(ProviderException.class, () -> model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), delta -> {}));
                assertFalse(failure.toString().contains("secret"));
                assertNull(failure.getCause());
            }
            assertEquals(0, captures.get());
        } finally { destination.stop(0); }
    }

    @Test void headerOverrideAndObserverFailureDoNotCorruptStream() throws Exception {
        AtomicReference<List<String>> auth = new AtomicReference<>();
        start(exchange -> {
            auth.set(exchange.getRequestHeaders().get("Authorization")); open(exchange);
            event(exchange, chunk("{\"content\":\"done\"}", "stop")); event(exchange, "[DONE]");
        });
        var result = model("openai-compatible", Duration.ofSeconds(3), Map.of("authorization", "Bearer custom-secret"))
                .complete(request(), delta -> { throw new IllegalStateException("observer-secret"); });
        assertEquals("done", result.text());
        assertEquals(List.of("Bearer custom-secret"), auth.get());
    }

    @Test void sseMultilineDataCommentsAndCrLfAreDecoded() throws Exception {
        start(exchange -> {
            open(exchange);
            String frame = ": keepalive\r\nevent: message\r\nid: 1\r\ndata: {\"choices\":[\r\ndata: {\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\r\n\r\ndata: [DONE]\r\n\r\n";
            exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
        });
        assertEquals("done", model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), delta -> {}).text());
    }

    @Test void bomCrOnlyFramingAndSplitUtf8PreserveVisibleText() throws Exception {
        start(exchange -> {
            open(exchange);
            String frame = "\uFEFF: comment\rdata: " + chunk("{\"content\":\"中😀\"}", "stop") + "\r\rdata: [DONE]\r\r";
            for (byte value : frame.getBytes(StandardCharsets.UTF_8)) {
                exchange.getResponseBody().write(value & 0xff);
                exchange.getResponseBody().flush();
            }
        });
        var deltas = new CopyOnWriteArrayList<String>();
        assertEquals("中😀", model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), deltas::add).text());
        assertEquals("中😀", String.join("", deltas));
    }

    @Test void incompleteDelimiterInvalidUtf8AndUnsupportedMediaFailClosed() throws Exception {
        AtomicInteger scenario = new AtomicInteger();
        start(exchange -> {
            if (scenario.get() == 2) {
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(completedJson().getBytes(StandardCharsets.UTF_8));
            } else {
                open(exchange);
                event(exchange, chunk("{\"content\":\"visible\"}", "stop"));
                if (scenario.get() == 0) exchange.getResponseBody().write("data: [DONE]\n".getBytes(StandardCharsets.UTF_8));
                else exchange.getResponseBody().write(new byte[]{(byte) 0xc3, (byte) 0x28});
            }
        });
        for (int i = 0; i < 3; i++) {
            scenario.set(i);
            var failure = assertThrows(ProviderException.class, () -> model("openai-compatible", Duration.ofSeconds(3), Map.of()).complete(request(), delta -> {}));
            assertNull(failure.getCause());
        }
    }

    private static String chunk(String delta, String finish) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}";
    }
    private static String callChunk() { return chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"mutate\",\"arguments\":\"{}\"}}]}", null); }
    private static String completedJson() { return "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}"; }
    private interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private void start(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try { handler.handle(exchange); }
            catch (java.io.IOException ignored) { /* 超时或边界限制关闭流时，这是预期行为。 */ }
            catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        });
        server.start();
    }
    private static void open(HttpExchange exchange) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8"); exchange.sendResponseHeaders(200, 0);
    }
    private static void event(HttpExchange exchange, String value) throws Exception {
        exchange.getResponseBody().write(("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
    }
    private static void json(HttpExchange exchange, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private ChatModel model(String id, Duration timeout, Map<String, String> headers) {
        return new ProviderRegistry(List.of()).create(new ProviderConfig(id, "mock", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), "default-secret", timeout, headers));
    }
    private static ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("hello")), List.of(), 100); }
}
