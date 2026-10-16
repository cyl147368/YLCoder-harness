package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.agent.AgentListener;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 本地传输契约检查，不使用 API 凭据或外部请求。 */
class OpenAiResponsesProviderTest {
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    private static final String TOOL_OUTPUT = """
            [
              {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque+reasoning=="},
              {"type":"message","id":"msg_1","role":"assistant","status":"completed",
                "content":[{"type":"output_text","text":"Reading source","annotations":[]}]},
              {"type":"function_call","id":"fc_1","call_id":"call_read","name":"read_file",
                "arguments":"{\\\"path\\\":\\\"Answer.java\\\"}","status":"completed"},
              {"type":"function_call","id":"fc_2","call_id":"call_search","name":"search",
                "arguments":"{\\\"query\\\":\\\"answer\\\"}","status":"completed"}
            ]
            """;

    private static final String FINAL_OUTPUT = """
            [{"type":"reasoning","id":"rs_final","summary":[],"encrypted_content":"opaque-final+/=="},
             {"type":"message","id":"msg_final","role":"assistant","status":"completed","phase":"final_answer",
              "content":[{"type":"output_text","text":"Done.","annotations":[{"type":"custom_annotation","opaque":"keep-exact"}]}]}]
            """;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void preservesOrderedEncryptedReasoningAndCallsAcrossTwoTurns() throws Exception {
        var model = model(envelope("completed", TOOL_OUTPUT), textResponse("The answer is 42."));
        var tools = List.of(new ToolDefinition("read_file", "Read source", Map.of("type", "object")));
        var history = new ArrayList<>(List.of(ChatMessage.system("Code carefully"), ChatMessage.user("Fix Answer.java")));
        var first = model.complete(new ChatRequest(history, tools, 300));
        assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
        assertEquals("Reading source", first.text());
        assertEquals(List.of("call_read", "call_search"), first.toolCalls().stream().map(ToolCall::id).toList());
        assertEquals(AbstractHttpChatModel.JSON.readTree(TOOL_OUTPUT),
                AbstractHttpChatModel.JSON.readTree(first.providerMetadata().get(OpenAiResponsesProvider.OUTPUT_ITEMS)));
        assertTrue(first.toolCalls().getFirst().providerMetadata().isEmpty());
        assertTrue(first.toolCalls().get(1).providerMetadata().isEmpty());

        history.add(ChatMessage.assistantToolCalls(first.text(), first.toolCalls(), first.providerMetadata()));
        history.add(ChatMessage.toolResult(first.toolCalls().get(0), "class Answer {}"));
        history.add(ChatMessage.toolResult(first.toolCalls().get(1), "Answer.java:1"));
        var finalResponse = model.complete(new ChatRequest(history, tools, 300));
        assertEquals(FinishReason.STOP, finalResponse.finishReason());
        assertEquals("The answer is 42.", finalResponse.text());

        JsonNode firstRequest = requests.get(0);
        assertEquals("test", firstRequest.path("model").asText());
        assertFalse(firstRequest.path("store").asBoolean(true));
        assertEquals("reasoning.encrypted_content", firstRequest.path("include").get(0).asText());
        assertEquals(300, firstRequest.path("max_output_tokens").asInt());
        assertEquals("system", firstRequest.path("input").get(0).path("role").asText());
        assertEquals("function", firstRequest.path("tools").get(0).path("type").asText());
        assertEquals("read_file", firstRequest.path("tools").get(0).path("name").asText());
        assertFalse(firstRequest.path("tools").get(0).path("strict").asBoolean(true));

        var input = requests.get(1).path("input");
        var original = AbstractHttpChatModel.JSON.readTree(TOOL_OUTPUT);
        for (int i = 0; i < original.size(); i++) assertEquals(original.get(i), input.get(i + 2));
        assertEquals(8, input.size());
        assertEquals("function_call_output", input.get(6).path("type").asText());
        assertEquals("call_read", input.get(6).path("call_id").asText());
        assertEquals("class Answer {}", input.get(6).path("output").asText());
        assertEquals("call_search", input.get(7).path("call_id").asText());
        assertFalse(requests.get(1).has("previous_response_id"));
    }

    @Test void acceptsNullErrorAndReportsInputOutputAndCachedUsage() throws Exception {
        var model = model("""
                {"status":"completed","error":null,"incomplete_details":null,
                 "output":[{"type":"message","role":"assistant","status":"completed",
                    "content":[{"type":"output_text","text":"done"}]}],
                 "usage":{"input_tokens":100,"output_tokens":7,"input_tokens_details":{"cached_tokens":80}}}
                """);
        var response = model.complete(request());
        assertEquals(100, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(80, response.usage().cachedInputTokens());
        assertEquals("openai-responses", new ProviderRegistry().provider("openai-responses").id());
    }

    @Test void incompleteResponsesNeverExposePartiallyCompletedCalls() throws Exception {
        for (String reason : List.of("max_output_tokens", "content_filter")) {
            var model = model("""
                    {"status":"incomplete","incomplete_details":{"reason":"%s"},"output":[
                      {"type":"function_call","status":"completed","call_id":"one","name":"write_file","arguments":"{}"},
                      {"type":"function_call","status":"incomplete","call_id":"two","name":"write_file","arguments":"{"}
                    ]}
                    """.formatted(reason));
            var response = model.complete(request());
            assertEquals(reason.equals("max_output_tokens") ? FinishReason.LENGTH : FinishReason.CONTENT_FILTER, response.finishReason());
            assertTrue(response.toolCalls().isEmpty());
            assertFalse(response.providerMetadata().containsKey(OpenAiResponsesProvider.OUTPUT_ITEMS));
            assertEquals(UsageReporting.metadata(false, false, false), response.providerMetadata());
            server.stop(0);
        }
    }

    @Test void refusalsDoNotExposeOtherToolCalls() throws Exception {
        var model = model(envelope("completed", """
                [{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"Cannot help"}]},
                 {"type":"function_call","call_id":"one","name":"write_file","arguments":"{}"}]
                """));
        var response = model.complete(request());
        assertEquals(FinishReason.CONTENT_FILTER, response.finishReason());
        assertEquals("Cannot help", response.text());
        assertTrue(response.toolCalls().isEmpty());
        assertFalse(response.providerMetadata().containsKey(OpenAiResponsesProvider.OUTPUT_ITEMS));
    }

    @Test void rejectsMalformedUnfinishedUnsupportedOrUnreplayableOutput() throws Exception {
        for (String response : List.of(
                envelope("completed", "[]"),
                envelope("in_progress", "[]"),
                "{\"status\":\"completed\",\"output\":{}}",
                "{\"status\":\"completed\",\"output\":[],\"error\":{\"message\":\"secret\"}}",
                "{\"status\":\"completed\",\"output\":[],\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}",
                envelope("completed", "[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"in_progress\",\"content\":[]}]"),
                envelope("completed", "[{\"type\":\"web_search_call\",\"id\":\"ws_1\"}]"),
                envelope("completed", "[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_audio\"}]}]"),
                envelope("completed", "[{\"type\":\"function_call\",\"call_id\":\"one\",\"name\":\"read_file\",\"arguments\":\"[]\"}]"),
                envelope("completed", "[{\"type\":\"function_call\",\"call_id\":\"one\",\"name\":\"read_file\",\"arguments\":\"{}\"},{\"type\":\"function_call\",\"call_id\":\"one\",\"name\":\"read_file\",\"arguments\":\"{}\"}]"),
                envelope("completed", "[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]},{\"type\":\"function_call\",\"call_id\":\"one\",\"name\":\"read_file\",\"arguments\":\"{}\"}]"),
                textResponse("done").replace("\"output\":", "\"usage\":{\"input_tokens\":-1},\"output\":"))) {
            var model = model(response);
            ProviderException failure = assertThrows(ProviderException.class, () -> model.complete(request()), response);
            assertFalse(failure.getMessage().contains("secret"));
            server.stop(0);
        }
    }

    @Test void rejectsTamperedReplayBeforeMakingAnotherRequest() throws Exception {
        var model = model(envelope("completed", TOOL_OUTPUT));
        var first = model.complete(request());
        var decoded = first.toolCalls().getFirst();
        var original = new ToolCall(decoded.id(), decoded.name(), decoded.argumentsJson(), first.providerMetadata());
        for (ToolCall tampered : List.of(
                new ToolCall("other-id", original.name(), original.argumentsJson(), original.providerMetadata()),
                new ToolCall(original.id(), "write_file", original.argumentsJson(), original.providerMetadata()),
                new ToolCall(original.id(), original.name(), "{\"path\":\"Other.java\"}", original.providerMetadata()),
                new ToolCall(original.id(), original.name(), original.argumentsJson(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, "{}")))) {
            var calls = List.of(tampered, first.toolCalls().get(1));
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(
                    List.of(ChatMessage.user("fix"), ChatMessage.assistantToolCalls(first.text(), calls)), List.of(), 100)));
        }
        var duplicateMetadata = new ToolCall(first.toolCalls().get(1).id(), first.toolCalls().get(1).name(),
                first.toolCalls().get(1).argumentsJson(), original.providerMetadata());
        assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                ChatMessage.assistantToolCalls(first.text(), List.of(original, duplicateMetadata))), List.of(), 100)));
        assertEquals(1, requests.size());
    }

    @Test void encodesProviderNeutralHistoryWithoutFabricatedReasoning() throws Exception {
        var model = model(textResponse("done"));
        var call = new ToolCall("local-call", "read_file", "{}");
        model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                ChatMessage.assistantToolCalls("Reading", List.of(call)), ChatMessage.toolResult(call, "source")), List.of(), null));
        var request = requests.getFirst();
        assertFalse(request.has("max_output_tokens"));
        assertFalse(request.has("tools"));
        assertEquals("assistant", request.path("input").get(1).path("role").asText());
        assertEquals("function_call", request.path("input").get(2).path("type").asText());
        assertEquals("local-call", request.path("input").get(2).path("call_id").asText());
        assertEquals("function_call_output", request.path("input").get(3).path("type").asText());
    }

    @Test void finalReasoningTextSurvivesAgentCheckpointAndUserFollowUp() throws Exception {
        var model = model(envelope("completed", FINAL_OUTPUT), textResponse("Follow-up done."));
        var agent = new CodeAgent(model, new ToolRegistry(List.of()));
        var first = agent.run("Fix source", AgentRunOptions.defaults());
        assertEquals(AgentResult.Status.COMPLETED, first.status());
        var saved = first.transcript().getLast();
        assertEquals("Done.", saved.content());
        assertEquals(AbstractHttpChatModel.JSON.readTree(FINAL_OUTPUT),
                AbstractHttpChatModel.JSON.readTree(saved.providerMetadata().get(OpenAiResponsesProvider.OUTPUT_ITEMS)));
        var history = new ArrayList<>(first.transcript());
        history.add(ChatMessage.user("Explain the change"));
        var next = agent.resume(history, AgentRunOptions.defaults(), AgentListener.NOOP);
        assertEquals(AgentResult.Status.COMPLETED, next.status());
        var input = requests.get(1).path("input");
        var expected = AbstractHttpChatModel.JSON.readTree(FINAL_OUTPUT);
        int start = input.size() - expected.size() - 1;
        assertEquals(expected.get(0), input.get(start));
        assertEquals(expected.get(1), input.get(start + 1));
        assertEquals("Explain the change", input.get(input.size() - 1).path("content").asText());
    }

    @Test void replaysLegacyCarrierAndMatchingDualCopyOnlyOnce() throws Exception {
        var model = model(envelope("completed", TOOL_OUTPUT), textResponse("done"));
        var first = model.complete(request());
        var original = first.toolCalls().getFirst();
        var legacyCall = new ToolCall(original.id(), original.name(), original.argumentsJson(), first.providerMetadata());
        var calls = List.of(legacyCall, first.toolCalls().get(1));
        for (var metadata : List.of(Map.<String, String>of(), first.providerMetadata())) {
            model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                    ChatMessage.assistantToolCalls(first.text(), calls, metadata)), List.of(), 100));
            var input = requests.getLast().path("input");
            var expected = AbstractHttpChatModel.JSON.readTree(TOOL_OUTPUT);
            assertEquals(expected.size() + 1, input.size());
            for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), input.get(i + 1));
        }
    }

    @Test void rejectsFinalTurnTamperingMalformedMetadataAndDuplicateIdsBeforeHttp() throws Exception {
        var model = model(envelope("completed", FINAL_OUTPUT));
        var first = model.complete(request());
        var invalid = List.of(
                ChatMessage.assistant("different", first.providerMetadata()),
                ChatMessage.assistant(first.text(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, "{}")),
                ChatMessage.assistant(first.text(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, FINAL_OUTPUT + " {}")),
                ChatMessage.assistant(first.text(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, FINAL_OUTPUT.replace("\"phase\":", "\"phase\":\"tampered\",\"phase\":"))),
                ChatMessage.assistant(first.text(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, FINAL_OUTPUT.replace("encrypted_content", "missing_content"))),
                ChatMessage.assistant(first.text(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, FINAL_OUTPUT.replace("msg_final", "rs_final"))),
                new ChatMessage(ChatMessage.Role.USER, first.text(), List.of(), null, null, first.providerMetadata()),
                ChatMessage.assistantToolCalls(first.text(), List.of(new ToolCall("fabricated", "write_file", "{}")), first.providerMetadata()));
        for (var message : invalid) {
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"), message), List.of(), 100)));
        }
        var replay = ChatMessage.assistant(first.text(), first.providerMetadata());
        assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"), replay,
                ChatMessage.user("again"), replay), List.of(), 100)));
        assertEquals(1, requests.size());
    }

    @Test void rejectsConflictingDualCopiesAndNewCarrierCallMismatchBeforeHttp() throws Exception {
        var model = model(envelope("completed", TOOL_OUTPUT));
        var first = model.complete(request());
        var original = first.toolCalls().getFirst();
        var legacyConflict = new ToolCall(original.id(), original.name(), original.argumentsJson(),
                Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, TOOL_OUTPUT.replace("opaque+reasoning==", "other-reasoning")));
        var changedCall = new ToolCall(original.id(), "write_file", original.argumentsJson());
        for (var calls : List.of(List.of(legacyConflict, first.toolCalls().get(1)), List.of(changedCall, first.toolCalls().get(1)))) {
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                    ChatMessage.assistantToolCalls(first.text(), calls, first.providerMetadata())), List.of(), 100)));
        }
        assertEquals(1, requests.size());
    }

    @Test void rejectsReasoningOnlyAndFinalTextWithoutEncryptedReasoning() throws Exception {
        for (String output : List.of("[{\"type\":\"reasoning\",\"id\":\"rs_only\",\"summary\":[],\"encrypted_content\":\"opaque\"}]",
                FINAL_OUTPUT.replace("encrypted_content", "missing_content"))) {
            var model = model(envelope("completed", output));
            assertThrows(ProviderException.class, () -> model.complete(request()));
            server.stop(0);
        }
    }

    @Test void rejectsAmbiguousFunctionArgumentsInRawAndProjectedHistory() throws Exception {
        var model = model(envelope("completed", TOOL_OUTPUT));
        var first = model.complete(request());
        var original = first.toolCalls().getFirst();
        for (String arguments : List.of("{\"path\":\"ignored\",\"path\":\"Answer.java\"}", "{\"path\":\"Answer.java\"} {}")) {
            var badCall = new ToolCall(original.id(), original.name(), arguments);
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                    ChatMessage.assistantToolCalls(first.text(), List.of(badCall, first.toolCalls().get(1)), first.providerMetadata())), List.of(), 100)));
            var raw = (com.fasterxml.jackson.databind.node.ArrayNode) AbstractHttpChatModel.JSON.readTree(TOOL_OUTPUT);
            ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get(2)).put("arguments", arguments);
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("fix"),
                    ChatMessage.assistantToolCalls(first.text(), first.toolCalls(), Map.of(OpenAiResponsesProvider.OUTPUT_ITEMS, raw.toString()))), List.of(), 100)));
        }
        assertEquals(1, requests.size());
    }

    private ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("fix")), List.of(), 100); }

    private ChatModel model(String... responses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger index = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            requests.add(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = responses[Math.min(index.getAndIncrement(), responses.length - 1)].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new OpenAiResponsesProvider().create(new ProviderConfig("openai-responses", "test",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), null));
    }

    private static String envelope(String status, String output) {
        return "{\"status\":\"" + status + "\",\"output\":" + output + "}";
    }

    private static String textResponse(String text) {
        return envelope("completed", "[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}]");
    }
}
