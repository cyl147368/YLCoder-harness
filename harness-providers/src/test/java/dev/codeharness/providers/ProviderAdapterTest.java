package dev.codeharness.providers;

import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderAdapterTest {
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void openAiCompatibleEncodesToolsAndDecodesUsageAndCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/v1/chat/completions", """
                {"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],"usage":{"prompt_tokens":17,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":5}}}
                """, requestBody);

        var model = new OpenAiCompatibleProvider().create(config("openai-compatible"));
        var response = model.complete(new ChatRequest(
                List.of(ChatMessage.user("inspect")),
                List.of(new ToolDefinition("read_file", "Read a file", Map.of("type", "object"))), 80));

        assertTrue(requestBody.get().contains("\"max_tokens\":80"));
        assertTrue(requestBody.get().contains("\"read_file\""));
        assertEquals("read_file", response.toolCalls().getFirst().name());
        assertEquals("{\"path\":\"README.md\"}", response.toolCalls().getFirst().argumentsJson());
        assertEquals(17, response.usage().inputTokens());
        assertEquals(5, response.usage().cachedInputTokens());
    }

    @Test void anthropicEncodesSystemAndToolDefinitions() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/v1/messages", """
                {"content":[{"type":"text","text":"done"},{"type":"tool_use","id":"tool-1","name":"search","input":{"query":"needle"}}],"usage":{"input_tokens":8,"output_tokens":4,"cache_read_input_tokens":2},"stop_reason":"tool_use"}
                """, requestBody);

        var model = new AnthropicProvider().create(config("anthropic"));
        var response = model.complete(new ChatRequest(
                List.of(ChatMessage.system("You are concise"), ChatMessage.user("find")),
                List.of(new ToolDefinition("search", "Search source", Map.of("type", "object"))), 64));

        assertTrue(requestBody.get().contains("\"system\""));
        assertTrue(requestBody.get().contains("\"input_schema\""));
        assertEquals("done", response.text());
        assertEquals("{\"query\":\"needle\"}", response.toolCalls().getFirst().argumentsJson());
        assertEquals(2, response.usage().cachedInputTokens());
        assertEquals(10, response.usage().inputTokens());
    }

    @Test void geminiEncodesToolsAndDecodesFunctionCall() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/v1beta/models/test-model:generateContent", """
                {"candidates":[{"content":{"parts":[{"text":"checking"},{"functionCall":{"name":"read_file","args":{"path":"src/Main.java"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":2,"cachedContentTokenCount":3}}
                """, requestBody);

        var response = new GeminiProvider().create(config("gemini")).complete(new ChatRequest(
                List.of(ChatMessage.user("read source")),
                List.of(new ToolDefinition("read_file", "Read source", Map.of("type", "object"))), 120));

        assertTrue(requestBody.get().contains("functionDeclarations"));
        assertEquals("checking", response.text());
        assertEquals("read_file", response.toolCalls().getFirst().name());
        assertEquals("{\"path\":\"src/Main.java\"}", response.toolCalls().getFirst().argumentsJson());
        assertEquals(3, response.usage().cachedInputTokens());
    }

    @Test void registryIncludesBuiltInsAliasesAndSupportsCustomProvider() {
        ModelProvider custom = new ModelProvider() {
            @Override public String id() { return "vendor-x"; }
            @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) { return ignored -> dev.codeharness.core.model.ChatResponse.text("custom"); }
        };
        ProviderRegistry registry = new ProviderRegistry(List.of(custom));
        assertSame(registry.provider("openai"), registry.provider("ollama"));
        assertEquals("custom", registry.create(new ProviderConfig("vendor-x", "m", null, null)).complete(
                new ChatRequest(List.of(ChatMessage.user("x")), List.of(), null)).text());
    }

    @Test void providerFailureDoesNotExposeApiKey() throws Exception {
        startServer("/v1/messages", "{\"error\":\"no\"}", null, 401);
        ProviderConfig secretConfig = new ProviderConfig("anthropic", "m", baseUri(), "secret-value");
        ProviderException error = assertThrows(ProviderException.class, () -> new AnthropicProvider().create(secretConfig)
                .complete(new ChatRequest(List.of(ChatMessage.user("hello")), List.of(), null)));
        assertFalse(error.getMessage().contains("secret-value"));
        assertEquals(401, error.statusCode());
    }

    @Test void providerConfigToStringRedactsSecretsAndHeaders() {
        ProviderConfig config = new ProviderConfig("custom", "model", null, "secret-value", java.time.Duration.ofSeconds(10),
                Map.of("Authorization", "Bearer other-secret"));
        assertFalse(config.toString().contains("secret-value"));
        assertFalse(config.toString().contains("other-secret"));
        assertTrue(config.toString().contains("[redacted]"));
    }

    @Test void geminiRoundTripsPlainTextAndStructuredToolResults() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer("/v1beta/models/test-model:generateContent", """
                {"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}]}
                """, body);
        var read = new ToolCall("read-1", "read_file", "{\"path\":\"README.md\"}");
        var search = new ToolCall("search-1", "search", "{}");
        var response = new GeminiProvider().create(config("gemini")).complete(new ChatRequest(
                List.of(ChatMessage.user("inspect"), ChatMessage.assistantToolCalls("", List.of(read, search)),
                        ChatMessage.toolResult(read, "public class Example {}"), ChatMessage.toolResult(search, "{\"count\":2}")),
                List.of(), 100));
        var request = AbstractHttpChatModel.JSON.readTree(body.get());
        var results = request.path("contents").get(2).path("parts");
        assertEquals(3, request.path("contents").size());
        assertEquals(2, results.size());
        assertEquals("public class Example {}", results.get(0).path("functionResponse").path("response").path("output").asText());
        assertEquals(2, results.get(1).path("functionResponse").path("response").path("count").asInt());
        assertEquals("done", response.text());
    }

    @Test void anthropicGroupsParallelToolResultsInOneUserMessage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer("/v1/messages", "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\"}", body);
        var a = new ToolCall("a", "read_file", "{}");
        var b = new ToolCall("b", "search", "{}");
        new AnthropicProvider().create(config("anthropic")).complete(new ChatRequest(
                List.of(ChatMessage.user("inspect"), ChatMessage.assistantToolCalls("", List.of(a, b)),
                        ChatMessage.toolResult(a, "source"), ChatMessage.toolResult(b, "matches")), List.of(), 100));
        var request = AbstractHttpChatModel.JSON.readTree(body.get());
        assertEquals(3, request.path("messages").size());
        var results = request.path("messages").get(2).path("content");
        assertEquals(2, results.size());
        assertEquals("a", results.get(0).path("tool_use_id").asText());
        assertEquals("b", results.get(1).path("tool_use_id").asText());
    }

    @Test void customAuthorizationOverridesDefaultWithoutDuplicateHeaders() throws Exception {
        AtomicReference<List<String>> values = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            values.set(exchange.getRequestHeaders().get("Authorization"));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var config = new ProviderConfig("openai-compatible", "m", baseUri(), "default-secret",
                java.time.Duration.ofSeconds(5), Map.of("authorization", "Bearer custom-secret"));
        new OpenAiCompatibleProvider().create(config).complete(simpleRequest());
        assertEquals(List.of("Bearer custom-secret"), values.get());
    }

    @Test void nativeOpenAiAndAzureUseReasoningCompatibleOutputLimit() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}", body);
        var registry = new ProviderRegistry(List.of());
        for (String provider : List.of("openai", "azure-openai")) {
            registry.create(config(provider)).complete(simpleRequest());
            var request = AbstractHttpChatModel.JSON.readTree(body.get());
            assertEquals(100, request.path("max_completion_tokens").asInt());
            assertFalse(request.has("max_tokens"));
        }
    }

    @Test void neverFollowsRedirectsWithCredentials() throws Exception {
        var redirected = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/capture", exchange -> {
            redirected.incrementAndGet();
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
            var failure = assertThrows(ProviderException.class,
                    () -> new OpenAiCompatibleProvider().create(config("openai-compatible")).complete(simpleRequest()));
            assertEquals(307, failure.statusCode());
            assertEquals(0, redirected.get());
        } finally {
            destination.stop(0);
        }
    }

    @Test void malformedResponseDoesNotEchoSecretsThroughExceptionChain() throws Exception {
        startServer("/v1/chat/completions", "secret-value malformed", null);
        var failure = assertThrows(ProviderException.class,
                () -> new OpenAiCompatibleProvider().create(config("openai-compatible")).complete(simpleRequest()));
        assertFalse(failure.toString().contains("secret-value"));
        assertNull(failure.getCause());
    }

    @Test void missingResponseEnvelopeIsRejected() throws Exception {
        startServer("/v1/chat/completions", "{}", null);
        assertThrows(ProviderException.class,
                () -> new OpenAiCompatibleProvider().create(config("openai-compatible")).complete(simpleRequest()));
    }

    @Test void openAiContentFilterIsNotReportedAsSuccessfulStop() throws Exception {
        startServer("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"content_filter\"}]}", null);
        var response = new OpenAiCompatibleProvider().create(config("openai-compatible")).complete(simpleRequest());
        assertEquals(FinishReason.CONTENT_FILTER, response.finishReason());
    }

    @Test void geminiPromptBlockIsNotReportedAsSuccessfulStop() throws Exception {
        startServer("/v1beta/models/test-model:generateContent", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", null);
        var response = new GeminiProvider().create(config("gemini")).complete(simpleRequest());
        assertEquals(FinishReason.CONTENT_FILTER, response.finishReason());
    }

    @Test void rejectsSecretBearingOrMalformedEndpointComponents() {
        for (String uri : List.of("https://user:secret@example.com/v1", "https://example.com/v1?key=secret",
                "https://example.com/v1#fragment", "https:/missing-host/v1")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new ProviderConfig("openai-compatible", "m", java.net.URI.create(uri), null));
            assertFalse(failure.getMessage().contains("secret"));
        }
        var config = new ProviderConfig("openai-compatible", "m", java.net.URI.create("https://example.com/secret-route"), null);
        assertFalse(config.toString().contains("secret-route"));
    }

    private ChatRequest simpleRequest() {
        return new ChatRequest(List.of(ChatMessage.user("hello")), List.of(), 100);
    }

    private ProviderConfig config(String id) {
        String apiVersion = "gemini".equals(id) ? "/v1beta" : "/v1";
        String model = "gemini".equals(id) ? "test-model" : "test-model";
        return new ProviderConfig(id, model, baseUri(apiVersion), "test-secret");
    }

    private java.net.URI baseUri() { return baseUri("/v1"); }
    private java.net.URI baseUri(String apiVersion) { return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + apiVersion); }

    private void startServer(String path, String response, AtomicReference<String> requestBody) throws Exception {
        startServer(path, response, requestBody, 200);
    }

    private void startServer(String path, String response, AtomicReference<String> requestBody, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            if (requestBody != null) requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
