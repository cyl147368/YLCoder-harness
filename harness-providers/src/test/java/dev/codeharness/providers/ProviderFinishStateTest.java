package dev.codeharness.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFinishStateTest {
    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void rejectsMissingNullBlankNontextAndUnknownFinishMarkersWithOrWithoutCalls() throws Exception {
        startServer();
        var request = new ChatRequest(List.of(ChatMessage.user("fix")), List.of(), 100);
        for (boolean anthropic : List.of(false, true)) {
            var model = provider(anthropic).create(config(anthropic ? "anthropic" : "openai-compatible"));
            for (boolean calls : List.of(false, true)) {
                for (String marker : new String[]{null, "null", "\"\"", "\"   \"", "123", "{}", "[]", "true", "\"unknown-secret\""}) {
                    responseBody.set(envelope(anthropic, calls, marker));
                    var failure = assertThrows(ProviderException.class, () -> model.complete(request),
                            "anthropic=" + anthropic + ", calls=" + calls + ", marker=" + marker);
                    assertFalse(failure.getMessage().contains("unknown-secret"));
                    assertNull(failure.getCause());
                }
            }
        }
    }

    @Test void preservesRecognizedExplicitCompletionAndFailureStates() throws Exception {
        startServer();
        var request = new ChatRequest(List.of(ChatMessage.user("fix")), List.of(), 100);
        for (boolean anthropic : List.of(false, true)) {
            var model = provider(anthropic).create(config(anthropic ? "anthropic" : "openai"));
            var states = anthropic
                    ? Map.of("end_turn", FinishReason.STOP, "stop_sequence", FinishReason.STOP,
                    "tool_use", FinishReason.TOOL_CALLS, "max_tokens", FinishReason.LENGTH,
                    "model_context_window_exceeded", FinishReason.LENGTH, "refusal", FinishReason.CONTENT_FILTER)
                    : Map.of("stop", FinishReason.STOP, "tool_calls", FinishReason.TOOL_CALLS,
                    "length", FinishReason.LENGTH, "content_filter", FinishReason.CONTENT_FILTER);
            for (var state : states.entrySet()) {
                responseBody.set(envelope(anthropic, state.getValue() == FinishReason.TOOL_CALLS, "\"" + state.getKey() + "\""));
                assertEquals(state.getValue(), model.complete(request).finishReason(), state.getKey());
            }
            // 厂商显式返回 STOP 且附带调用时，保留现有兼容行为。
            responseBody.set(envelope(anthropic, true, anthropic ? "\"end_turn\"" : "\"stop\""));
            assertEquals(FinishReason.TOOL_CALLS, model.complete(request).finishReason());
        }
    }

    @Test void openAiAliasesAndAzureRejectMissingFinishMarker() throws Exception {
        startServer();
        var request = new ChatRequest(List.of(ChatMessage.user("fix")), List.of(), 100);
        responseBody.set(envelope(false, true, null));
        for (String id : List.of("openai", "openai-compatible", "ollama", "azure-openai")) {
            ModelProvider provider = "azure-openai".equals(id) ? new AzureOpenAiProvider() : new OpenAiCompatibleProvider();
            var model = provider.create(config(id));
            assertThrows(ProviderException.class, () -> model.complete(request), id);
        }
    }

    @Test void malformedProviderCompletionNeverExecutesMutationOrCompletesAgent() throws Exception {
        startServer();
        AtomicInteger mutations = new AtomicInteger();
        Tool recordingWrite = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("write_file", "Record mutation", Map.of("type", "object")); }
            @Override public String execute(String arguments) { mutations.incrementAndGet(); return "mutated"; }
        };
        ToolRegistry tools = new ToolRegistry(List.of(recordingWrite));
        for (boolean anthropic : List.of(false, true)) {
            for (boolean calls : List.of(false, true)) {
                for (String marker : new String[]{null, "null"}) {
                    responseBody.set(envelope(anthropic, calls, marker));
                    var model = provider(anthropic).create(config(anthropic ? "anthropic" : "openai"));
                    var result = new CodeAgent(model, tools).run("Fix Answer.java", AgentRunOptions.defaults());
                    assertEquals(AgentResult.Status.FAILED, result.status());
                    assertEquals(0, mutations.get(), "未确认的厂商轮次执行了修改");
                    assertFalse(result.transcript().stream().anyMatch(message -> message.role() == ChatMessage.Role.TOOL));
                }
            }
        }
    }

    private ModelProvider provider(boolean anthropic) {
        return anthropic ? new AnthropicProvider() : new OpenAiCompatibleProvider();
    }

    private ProviderConfig config(String id) {
        return new ProviderConfig(id, "model", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), null);
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private static String envelope(boolean anthropic, boolean calls, String marker) throws Exception {
        ObjectNode payload = AbstractHttpChatModel.JSON.createObjectNode();
        ObjectNode state;
        if (anthropic) {
            state = payload;
            var content = payload.putArray("content");
            if (calls) {
                var call = content.addObject().put("type", "tool_use").put("id", "call_write").put("name", "write_file");
                call.putObject("input").put("path", "Answer.java").put("content", "changed");
            } else content.addObject().put("type", "text").put("text", "done");
        } else {
            state = payload.putArray("choices").addObject();
            var message = state.putObject("message").put("role", "assistant").put("content", calls ? "" : "done");
            if (calls) message.putArray("tool_calls").addObject().put("id", "call_write").put("type", "function")
                    .putObject("function").put("name", "write_file").put("arguments", "{\"path\":\"Answer.java\",\"content\":\"changed\"}");
        }
        if (marker != null) state.set(anthropic ? "stop_reason" : "finish_reason", AbstractHttpChatModel.JSON.readTree(marker));
        return payload.toString();
    }
}
