package dev.codeharness.providers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jStreamingTest {
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    @AfterEach void stop() { release.countDown(); if (server != null) server.stop(0); }

    @Test void deliversRealDeltasBeforeCompletionAndCollectsUsage() throws Exception {
        var received = new CountDownLatch(1);
        var input = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        start(exchange -> {
            input.set(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            open(exchange);
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}");
            assertTrue(release.await(5, TimeUnit.SECONDS));
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}");
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
            event(exchange, "{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13,\"prompt_tokens_details\":{\"cached_tokens\":4}}}");
            event(exchange, "[DONE]");
        });
        var deltas = new CopyOnWriteArrayList<String>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> model(Duration.ofSeconds(5)).complete(request(), delta -> {
                deltas.add(delta); received.countDown();
            }));
            assertTrue(received.await(3, TimeUnit.SECONDS), "服务端保持完成状态期间应收到增量");
            assertFalse(future.isDone());
            release.countDown();
            var response = future.get(5, TimeUnit.SECONDS);
            assertEquals("Hello", String.join("", deltas));
            assertEquals("Hello", response.text());
            assertEquals(FinishReason.STOP, response.finishReason());
            assertEquals(11, response.usage().inputTokens());
            assertEquals(4, response.usage().cachedInputTokens());
            assertTrue(input.get().path("stream").asBoolean());
        }
    }

    @Test void assemblesFragmentedToolArgumentsWithoutExecutingPartialCalls() throws Exception {
        start(exchange -> {
            open(exchange);
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"edit-1\",\"type\":\"function\",\"function\":{\"name\":\"edit_file\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}");
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"A.java\\\"}\"}}]}}]}");
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
            event(exchange, "[DONE]");
        });
        var deltas = new CopyOnWriteArrayList<String>();
        var response = model(Duration.ofSeconds(5)).complete(request(), deltas::add);
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(List.of(new ToolCall("edit-1", "edit_file", "{\"path\":\"A.java\"}")), response.toolCalls());
        assertTrue(deltas.isEmpty());
    }

    @Test void rejectsStreamClosedBeforeFinishReason() throws Exception {
        start(exchange -> {
            open(exchange);
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Partial\"}}]}");
        });
        assertThrows(ProviderException.class,
                () -> model(Duration.ofSeconds(2)).complete(request(), delta -> {}));
    }

    @Test void observerFailureDoesNotAbortGeneration() throws Exception {
        start(exchange -> {
            open(exchange);
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Done\"}}]}");
            event(exchange, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
            event(exchange, "[DONE]");
        });
        var response = model(Duration.ofSeconds(5)).complete(request(), delta -> { throw new IllegalStateException("Broken display"); });
        assertEquals("Done", response.text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test void timesOutIncompleteStreamWithoutLeakingContent() throws Exception {
        start(exchange -> { open(exchange); release.await(5, TimeUnit.SECONDS); });
        var failure = assertThrows(ProviderException.class,
                () -> model(Duration.ofMillis(200)).complete(request(), delta -> {}));
        assertNull(failure.getCause());
        assertTrue(failure.getMessage().contains("超时") || failure.getMessage().contains("失败"));
    }

    @Test void streamHttpErrorIsRedacted() throws Exception {
        start(exchange -> {
            var bytes = "{\"error\":{\"message\":\"secret-key-echo\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        var failure = assertThrows(ProviderException.class,
                () -> model(Duration.ofSeconds(5)).complete(request(), delta -> {}));
        assertFalse(failure.toString().contains("secret-key-echo"));
        assertNull(failure.getCause());
    }

    @Test void interruptCancelsWaitingRequestAndPreservesFlag() throws Exception {
        var arrived = new CountDownLatch(1);
        start(exchange -> { open(exchange); arrived.countDown(); release.await(5, TimeUnit.SECONDS); });
        var interrupted = new AtomicReference<Boolean>();
        var failure = new AtomicReference<Throwable>();
        var model = model(Duration.ofSeconds(5));
        var thread = new Thread(() -> {
            try { model.complete(request(), delta -> {}); }
            catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        thread.start();
        assertTrue(arrived.await(3, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertInstanceOf(ProviderException.class, failure.get());
        assertEquals(Boolean.TRUE, interrupted.get());
    }

    private interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private void start(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try { handler.handle(exchange); }
            catch (Exception ignored) { /* 客户端取消可能关闭流。 */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    private void open(HttpExchange exchange) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }
    private void event(HttpExchange exchange, String json) throws Exception {
        exchange.getResponseBody().write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
    private ChatModel model(Duration timeout) {
        return new LangChain4jOpenAiProvider().create(new ProviderConfig("langchain4j-openai", "mock",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), "test-key", timeout, Map.of()));
    }
    private ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("hello")), List.of(), 100); }
}
