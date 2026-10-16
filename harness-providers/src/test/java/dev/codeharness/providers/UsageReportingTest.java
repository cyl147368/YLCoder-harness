package dev.codeharness.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UsageReportingTest {
    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai-responses", "anthropic", "gemini", "langchain4j-openai"})
    void distinguishesMissingExplicitZeroAndPositiveUsage(String provider) throws Exception {
        var model = new ProviderRegistry(List.of()).create(start(provider));
        for (int amount : new int[] {-1, 0, 12}) {
            responseBody.set(payload(provider, amount).toString());
            ChatResponse response = model.complete(request());
            assertEquals("完成", response.text());
            assertEquals(FinishReason.STOP, response.finishReason());
            assertReported(response, amount >= 0, amount >= 0, amount >= 0);
            long input = amount < 0 ? 0 : "anthropic".equals(provider) ? amount * 3L : amount;
            assertEquals(new TokenUsage(input, Math.max(0, amount), Math.max(0, amount)), response.usage());
            if ("gemini".equals(provider)) assertTrue(response.providerMetadata().containsKey(GeminiProvider.CONTENT_PARTS));
            if ("openai-responses".equals(provider)) assertTrue(response.providerMetadata().containsKey(OpenAiResponsesProvider.OUTPUT_ITEMS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai-responses", "anthropic", "gemini", "langchain4j-openai"})
    void inputAndOutputDoNotImplyCacheReporting(String provider) throws Exception {
        var model = new ProviderRegistry(List.of()).create(start(provider));
        ObjectNode payload = payload(provider, 12);
        ObjectNode usage = (ObjectNode) payload.get("gemini".equals(provider) ? "usageMetadata" : "usage");
        switch (provider) {
            case "anthropic" -> usage.remove("cache_read_input_tokens");
            case "gemini" -> usage.remove("cachedContentTokenCount");
            case "openai-responses" -> usage.remove("input_tokens_details");
            default -> usage.remove("prompt_tokens_details");
        }
        responseBody.set(payload.toString());
        ChatResponse response = model.complete(request());
        assertReported(response, true, true, false);
        assertEquals(0, response.usage().cachedInputTokens());
        assertEquals("anthropic".equals(provider) ? 24 : 12, response.usage().inputTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai-responses", "anthropic", "gemini", "langchain4j-openai"})
    void nullUsageIsUnavailableAndIndividualMissingFieldsStayIndependent(String provider) throws Exception {
        var model = new ProviderRegistry(List.of()).create(start(provider));
        ObjectNode payload = payload(provider, -1);
        String usageField = "gemini".equals(provider) ? "usageMetadata" : "usage";
        payload.putNull(usageField);
        responseBody.set(payload.toString());
        assertReported(model.complete(request()), false, false, false);

        payload = payload(provider, 12);
        ObjectNode usage = (ObjectNode) payload.get(usageField);
        String outputField = switch (provider) {
            case "gemini" -> "candidatesTokenCount";
            case "openai-responses", "anthropic" -> "output_tokens";
            default -> "completion_tokens";
        };
        usage.putNull(outputField);
        responseBody.set(payload.toString());
        var response = model.complete(request());
        assertReported(response, true, false, true);
        assertEquals(0, response.usage().outputTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "langchain4j-openai"})
    void streamingReportsFinalUsageAndKeepsMissingUsageUnknown(String provider) throws Exception {
        var model = new ProviderRegistry(List.of()).create(start(provider, true));
        for (int amount : new int[] {-1, 0, 12}) {
            String usage = amount < 0 ? "" : "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":" + amount
                    + ",\"completion_tokens\":" + amount + ",\"total_tokens\":" + amount * 2
                    + ",\"prompt_tokens_details\":{\"cached_tokens\":" + amount + "}}}\n\n";
            responseBody.set("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"完成\"}}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + usage + "data: [DONE]\n\n");
            var text = new StringBuilder();
            ChatResponse response = model.complete(request(), text::append);
            assertEquals("完成", text.toString());
            assertEquals("完成", response.text());
            assertReported(response, amount >= 0, amount >= 0, amount >= 0);
            assertEquals(new TokenUsage(Math.max(0, amount), Math.max(0, amount), Math.max(0, amount)), response.usage());
        }
    }

    @Test void invalidOrUnavailableFieldsAreNotReportedCounts() throws Exception {
        for (String value : List.of("null", "\"0\"", "false", "0.5", "-1", "9223372036854775808", "{}", "[]")) {
            var node = AbstractHttpChatModel.JSON.readTree("{\"count\":" + value + "}");
            assertFalse(UsageReporting.reported(node, "count"), value);
        }
        assertFalse(UsageReporting.reported(AbstractHttpChatModel.JSON.createObjectNode(), "count"));
        assertFalse(UsageReporting.reported((Integer) null));
        assertFalse(UsageReporting.reported(-1));
        assertTrue(UsageReporting.reported(0));
    }

    @Test void filteredAndTruncatedStreamResponsesRetainReportingFlags() {
        for (String finish : List.of("content_filter", "length")) {
            var parser = new OpenAiChatStream(null);
            parser.onEvent("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}");
            parser.onEvent("{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}");
            parser.onEvent("[DONE]");
            assertReported(parser.response(), true, true, false);
            assertTrue(parser.response().toolCalls().isEmpty());
        }
    }

    @Test void metadataAdditionPreservesProtocolContinuityFields() {
        var metadata = UsageReporting.with(Map.of("gemini.thoughtSignature", "opaque", "openai.responses.outputItems", "[]"), true, false, true);
        assertEquals("opaque", metadata.get("gemini.thoughtSignature"));
        assertEquals("[]", metadata.get("openai.responses.outputItems"));
        assertEquals("false", metadata.get("usage.outputReported"));
        assertEquals(5, metadata.size());
    }

    private static void assertReported(ChatResponse response, boolean input, boolean output, boolean cache) {
        assertEquals(Boolean.toString(input), response.providerMetadata().get("usage.inputReported"));
        assertEquals(Boolean.toString(output), response.providerMetadata().get("usage.outputReported"));
        assertEquals(Boolean.toString(cache), response.providerMetadata().get("usage.cacheReported"));
    }

    private ProviderConfig start(String provider) throws Exception { return start(provider, false); }

    private ProviderConfig start(String provider, boolean stream) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new ProviderConfig(provider, "test-model", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-key", Duration.ofSeconds(5), Map.of());
    }

    private static ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("检查用量")), List.of(), 100); }

    private static ObjectNode payload(String provider, int amount) throws Exception {
        String envelope = switch (provider) {
            case "openai-responses" -> "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"完成\"}]}]}";
            case "anthropic" -> "{\"content\":[{\"type\":\"text\",\"text\":\"完成\"}],\"stop_reason\":\"end_turn\"}";
            case "gemini" -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完成\"}]},\"finishReason\":\"STOP\"}]}";
            default -> "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"完成\"}}]}";
        };
        ObjectNode payload = (ObjectNode) AbstractHttpChatModel.JSON.readTree(envelope);
        if (amount >= 0) {
            ObjectNode usage = payload.putObject("gemini".equals(provider) ? "usageMetadata" : "usage");
            switch (provider) {
                case "openai-responses" -> {
                    usage.put("input_tokens", amount).put("output_tokens", amount);
                    usage.putObject("input_tokens_details").put("cached_tokens", amount);
                }
                case "anthropic" -> usage.put("input_tokens", amount).put("output_tokens", amount)
                        .put("cache_read_input_tokens", amount).put("cache_creation_input_tokens", amount);
                case "gemini" -> usage.put("promptTokenCount", amount).put("candidatesTokenCount", amount).put("cachedContentTokenCount", amount);
                default -> {
                    usage.put("prompt_tokens", amount).put("completion_tokens", amount).put("total_tokens", amount * 2);
                    usage.putObject("prompt_tokens_details").put("cached_tokens", amount);
                }
            }
        }
        return payload;
    }
}
