package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jOpenAiProviderTest {
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final AtomicReference<List<String>> authorization = new AtomicReference<>();

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void registryExposesGenuineLangChain4jAdapter() {
        assertInstanceOf(LangChain4jOpenAiProvider.class, new ProviderRegistry(List.of()).provider("langchain4j-openai"));
    }

    @Test void mapsMessagesToolSchemaCallResultsAndUsageAcrossTwoTurns() throws Exception {
        startServer(List.of("""
                {"id":"chat1","model":"test-model","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"Inspecting","tool_calls":[{"id":"read-1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],"usage":{"prompt_tokens":17,"completion_tokens":3,"total_tokens":20,"prompt_tokens_details":{"cached_tokens":5}}}
                """, """
                {"id":"chat2","model":"test-model","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Done"}}],"usage":{"prompt_tokens":25,"completion_tokens":2,"total_tokens":27}}
                """), 200);
        var model = new ProviderRegistry(List.of()).create(config(Map.of()));
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(
                "path", Map.of("type", "string", "description", "Relative source path"),
                "options", Map.of("type", "object", "properties", Map.of("lines", Map.of("type", "integer"))),
                "tags", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("path"), "additionalProperties", false);
        var tools = List.of(new ToolDefinition("read_file", "Read a source file", schema));
        var history = new ArrayList<>(List.of(ChatMessage.system("Be concise"), ChatMessage.user("Inspect source")));
        var first = model.complete(new ChatRequest(history, tools, 140));
        assertEquals("Inspecting", first.text());
        assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
        assertEquals(new ToolCall("read-1", "read_file", "{\"path\":\"README.md\"}"), first.toolCalls().getFirst());
        assertEquals(17, first.usage().inputTokens());
        assertEquals(3, first.usage().outputTokens());
        assertEquals(5, first.usage().cachedInputTokens());
        history.add(ChatMessage.assistantToolCalls(first.text(), first.toolCalls()));
        history.add(ChatMessage.toolResult(first.toolCalls().getFirst(), "# Harness\nSource text"));
        var second = model.complete(new ChatRequest(history, tools, 140));
        assertEquals("Done", second.text());
        assertEquals(FinishReason.STOP, second.finishReason());
        assertEquals(2, requests.size());
        assertEquals(List.of("Bearer test-key"), authorization.get());
        JsonNode firstRequest = requests.getFirst();
        assertEquals(140, firstRequest.path("max_completion_tokens").asInt());
        assertEquals("test-model", firstRequest.path("model").asText());
        assertEquals("system", firstRequest.path("messages").get(0).path("role").asText());
        JsonNode parameters = firstRequest.path("tools").get(0).path("function").path("parameters");
        assertEquals("integer", parameters.path("properties").path("options").path("properties").path("lines").path("type").asText());
        assertEquals("string", parameters.path("properties").path("tags").path("items").path("type").asText());
        assertEquals("path", parameters.path("required").get(0).asText());
        assertFalse(parameters.path("additionalProperties").asBoolean());
        JsonNode followup = requests.get(1).path("messages");
        assertEquals("read-1", followup.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("read-1", followup.get(3).path("tool_call_id").asText());
        assertEquals("# Harness\nSource text", followup.get(3).path("content").asText());
    }

    @Test void customAuthenticationOverridesSdkDefault() throws Exception {
        startServer(List.of(success()), 200);
        new LangChain4jOpenAiProvider().create(config(Map.of("authorization", "Bearer override"))).complete(request());
        assertEquals(List.of("Bearer override"), authorization.get());
    }

    @Test void sdkFailureIsSecretSafeAndNotRetried() throws Exception {
        startServer(List.of("{\"error\":{\"message\":\"server echoed secret-key\"}}"), 500);
        var error = assertThrows(ProviderException.class,
                () -> new LangChain4jOpenAiProvider().create(config(Map.of())).complete(request()));
        assertFalse(error.toString().contains("secret-key"));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
    }

    @Test void contentFilterFinishReasonIsPreserved() throws Exception {
        startServer(List.of("{\"choices\":[{\"index\":0,\"finish_reason\":\"content_filter\",\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}"), 200);
        assertEquals(FinishReason.CONTENT_FILTER,
                new LangChain4jOpenAiProvider().create(config(Map.of())).complete(request()).finishReason());
    }

    @Test void malformedResponseFailsWithoutBodyLeak() throws Exception {
        startServer(List.of("secret-response"), 200);
        var error = assertThrows(ProviderException.class,
                () -> new LangChain4jOpenAiProvider().create(config(Map.of())).complete(request()));
        assertFalse(error.toString().contains("secret-response"));
        assertNull(error.getCause());
    }

    @Test void redirectNeverForwardsCredentialsToAnotherOrigin() throws Exception {
        var forwarded = new AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/capture", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        destination.start();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/capture");
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
            server.start();
            assertThrows(ProviderException.class,
                    () -> new LangChain4jOpenAiProvider().create(config(Map.of())).complete(request()));
            assertEquals(0, forwarded.get());
        } finally {
            destination.stop(0);
        }
    }

    private void startServer(List<String> responses, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var index = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().get("Authorization"));
            var bytes = responses.get(Math.min(index.getAndIncrement(), responses.size() - 1)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private ProviderConfig config(Map<String, String> headers) {
        return new ProviderConfig("langchain4j-openai", "test-model",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-key", Duration.ofSeconds(5), headers);
    }

    private ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("hello")), List.of(), 100); }
    private String success() { return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"; }
}
