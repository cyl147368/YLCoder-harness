package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeminiMetadataTest {
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void preservesExactOrderedThoughtTextAndParallelCallParts() throws Exception {
        String rawParts = """
                [{"text":"private thought","thought":true,"thoughtSignature":"thought-signature"},
                 {"text":"Reading ","thoughtSignature":"text-signature"},
                 {"functionCall":{"id":"wire-a","name":"read_file","args":{"path":"a.java"}},"thoughtSignature":"call-signature"},
                 {"text":"both"},
                 {"functionCall":{"name":"read_file","args":{"path":"b.java"}}}]
                """;
        var model = model(response(rawParts, "STOP"));
        var first = model.complete(request());
        assertEquals("Reading both", first.text());
        assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
        assertEquals(AbstractHttpChatModel.JSON.readTree(rawParts),
                AbstractHttpChatModel.JSON.readTree(first.providerMetadata().get(GeminiProvider.CONTENT_PARTS)));
        var history = new ArrayList<>(List.of(ChatMessage.user("read"),
                ChatMessage.assistantToolCalls(first.text(), first.toolCalls(), first.providerMetadata())));
        for (ToolCall call : first.toolCalls()) history.add(ChatMessage.toolResult(call, "source"));
        model.complete(new ChatRequest(history, List.of(), 100));
        assertEquals(AbstractHttpChatModel.JSON.readTree(rawParts), requests.get(1).path("contents").get(1).path("parts"));
        var results = requests.get(1).path("contents").get(2).path("parts");
        assertEquals("wire-a", results.get(0).path("functionResponse").path("id").asText());
        assertFalse(results.get(1).path("functionResponse").has("id"));
    }

    @Test void finalThoughtAndTextPartsSurviveSerializationAndLaterUserFollowup() throws Exception {
        String parts = """
                [{"text":"hidden","thought":true,"thoughtSignature":"opaque-thought=="},
                 {"text":"done","thoughtSignature":"opaque-final=="}]
                """;
        var model = model(response(parts, "STOP"));
        var first = model.complete(request());
        assertEquals("done", first.text());
        assertTrue(first.toolCalls().isEmpty());
        var message = ChatMessage.assistant(first.text(), first.providerMetadata());
        var restored = AbstractHttpChatModel.JSON.readValue(AbstractHttpChatModel.JSON.writeValueAsString(message), ChatMessage.class);
        model.complete(new ChatRequest(List.of(ChatMessage.user("read"), restored, ChatMessage.user("follow up")), List.of(), 100));
        assertEquals(AbstractHttpChatModel.JSON.readTree(parts), requests.get(1).path("contents").get(1).path("parts"));
    }

    @Test void tamperedReplayProjectionFailsBeforeHttp() throws Exception {
        var model = model(response("""
                [{"text":"read"},{"functionCall":{"id":"wire-a","name":"read_file","args":{"path":"a"}},"thoughtSignature":"signed"}]
                """, "STOP"));
        var first = model.complete(request());
        var original = first.toolCalls().getFirst();
        var badMessages = List.of(
                ChatMessage.assistantToolCalls("changed", first.toolCalls(), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", "write_file", original.argumentsJson(), original.providerMetadata())), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", original.name(), "{}", original.providerMetadata())), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", original.name(), "{\"path\":\"wrong\",\"path\":\"a\"}", original.providerMetadata())), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", original.name(), original.argumentsJson() + " {}", original.providerMetadata())), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("different", original.name(), original.argumentsJson(), original.providerMetadata())), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", original.name(), original.argumentsJson(), Map.of(GeminiProvider.FUNCTION_CALL_ID, "wire-a", GeminiProvider.THOUGHT_SIGNATURE, "changed"))), first.providerMetadata()),
                ChatMessage.assistantToolCalls("read", List.of(new ToolCall("wire-a", original.name(), original.argumentsJson())), first.providerMetadata()),
                ChatMessage.assistant("read", Map.of(GeminiProvider.CONTENT_PARTS, "{")),
                ChatMessage.assistant("read", Map.of(GeminiProvider.CONTENT_PARTS, "{}")),
                ChatMessage.assistant("read", Map.of(GeminiProvider.CONTENT_PARTS, "[{\"text\":\"wrong\",\"text\":\"read\"}]")),
                ChatMessage.assistant("read", Map.of(GeminiProvider.CONTENT_PARTS, "[{\"text\":\"read\"}] []")),
                new ChatMessage(ChatMessage.Role.USER, "read", List.of(), null, null, first.providerMetadata()));
        for (var message : badMessages) {
            var error = assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(message), List.of(), 100)));
            assertFalse(error.getMessage().contains("signed"));
        }
        assertEquals(1, requests.size());
    }

    @Test void unsupportedAndMalformedPartsFailClosed() throws Exception {
        var badParts = List.of("[{}]", "[null]", "[42]", "[{\"text\":null}]", "[{\"text\":42}]",
                "[{\"text\":\"x\",\"inlineData\":{}}]", "[{\"inlineData\":{}}]",
                "[{\"text\":\"x\",\"functionCall\":{\"name\":\"read\"}}]",
                "[{\"text\":\"x\",\"thought\":\"true\"}]", "[{\"text\":\"x\",\"thoughtSignature\":null}]",
                "[{\"text\":\"x\",\"thoughtSignature\":\"\"}]",
                "[{\"functionCall\":{\"name\":\"read\",\"id\":23}}]",
                "[{\"functionCall\":{\"name\":\"read\",\"args\":[]}}]",
                "[{\"functionCall\":{\"name\":\"read\",\"unknown\":true}}]",
                "[{\"functionCall\":{\"name\":\"read\"},\"thought\":true}]",
                "[{\"functionCall\":{\"name\":\"read\",\"id\":\"a\"}},{\"functionCall\":{\"name\":\"read\",\"id\":\"a\"}}]",
                "[{\"text\":\"hidden\",\"thought\":true}]", "[{\"text\":\"  \"}]", "[]");
        for (String parts : badParts) {
            var model = model(response(parts, "STOP"));
            assertThrows(ProviderException.class, () -> model.complete(request()), parts);
            int previousRequests = requests.size();
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(
                    List.of(ChatMessage.assistant("", Map.of(GeminiProvider.CONTENT_PARTS, parts))), List.of(), 100)), parts);
            assertEquals(previousRequests, requests.size());
            server.stop(0);
        }
    }

    @Test void incompleteAndRefusedPartsNeverExposeCallsOrReplayEvidence() throws Exception {
        for (String reason : List.of("MAX_TOKENS", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY")) {
            var model = model(response("""
                    [{"text":"partial"},{"functionCall":{"name":"write_file","args":{"path":"x"}},"thoughtSignature":"opaque"}]
                    """, reason));
            var result = model.complete(request());
            assertTrue(result.toolCalls().isEmpty());
            assertFalse(result.providerMetadata().containsKey(GeminiProvider.CONTENT_PARTS));
            assertEquals(UsageReporting.metadata(false, false, false), result.providerMetadata());
            assertEquals("MAX_TOKENS".equals(reason) ? FinishReason.LENGTH : FinishReason.CONTENT_FILTER, result.finishReason());
            server.stop(0);
        }
    }

    @Test void malformedLegacyMetadataAndArgumentsFailBeforeHttp() throws Exception {
        var model = model(response("[{\"text\":\"ok\"}]", "STOP"));
        for (ToolCall call : List.of(
                new ToolCall("a", "read", "{}", Map.of(GeminiProvider.FUNCTION_CALL_ID, "b")),
                new ToolCall("a", "read", "{}", Map.of(GeminiProvider.THOUGHT_SIGNATURE, " ")),
                new ToolCall("a", "read", "{}", Map.of(GeminiProvider.CONTENT_PARTS, "[]")),
                new ToolCall("a", "read", "{\"path\":\"a\",\"path\":\"b\"}"),
                new ToolCall("a", "read", "{} {}"))) {
            assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(
                    List.of(ChatMessage.assistantToolCalls("", List.of(call))), List.of(), 100)));
        }
        assertEquals(0, requests.size());
    }

    private static String response(String parts, String finish) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":" + parts + "},\"finishReason\":\"" + finish + "\"}]}";
    }

    @Test void preservesSignaturesAndCallIdsOnMatchingPartsAcrossTwoTurns() throws Exception {
        var model = model("""
                {"candidates":[{"content":{"parts":[
                  {"text":"private thought","thought":true},
                  {"text":"Reading"},
                  {"functionCall":{"id":"server-a","name":"read_file","args":{"path":"a.java"}},"thoughtSignature":"opaque+signature/a=="},
                  {"functionCall":{"id":"server-b","name":"read_file","args":{"path":"b.java"}}}
                ]},"finishReason":"STOP"}]}
                """);
        var history = new ArrayList<>(List.of(ChatMessage.user("read both")));
        var first = model.complete(new ChatRequest(history, List.of(), 100));
        assertEquals("Reading", first.text());
        assertEquals("server-a", first.toolCalls().get(0).id());
        assertEquals("opaque+signature/a==", first.toolCalls().get(0).providerMetadata().get(GeminiProvider.THOUGHT_SIGNATURE));
        assertFalse(first.toolCalls().get(1).providerMetadata().containsKey(GeminiProvider.THOUGHT_SIGNATURE));
        history.add(ChatMessage.assistantToolCalls(first.text(), first.toolCalls()));
        for (ToolCall call : first.toolCalls()) history.add(ChatMessage.toolResult(call, "source for " + call.id()));
        model.complete(new ChatRequest(history, List.of(), 100));
        var contents = requests.get(1).path("contents");
        var parts = contents.get(1).path("parts");
        assertEquals("Reading", parts.get(0).path("text").asText());
        assertEquals("server-a", parts.get(1).path("functionCall").path("id").asText());
        assertEquals("opaque+signature/a==", parts.get(1).path("thoughtSignature").asText());
        assertEquals("server-b", parts.get(2).path("functionCall").path("id").asText());
        assertFalse(parts.get(2).has("thoughtSignature"));
        var results = contents.get(2).path("parts");
        assertEquals("server-a", results.get(0).path("functionResponse").path("id").asText());
        assertEquals("server-b", results.get(1).path("functionResponse").path("id").asText());
    }

    @Test void absentWireIdsAndSignaturesAreNeverFabricated() throws Exception {
        var model = model("{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read_file\",\"args\":{}}}]},\"finishReason\":\"STOP\"}]}");
        var first = model.complete(request());
        var call = first.toolCalls().getFirst();
        assertTrue(call.providerMetadata().isEmpty());
        model.complete(new ChatRequest(List.of(ChatMessage.user("read"), ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "source")), List.of(), 100));
        var contents = requests.get(1).path("contents");
        var part = contents.get(1).path("parts").get(0);
        assertFalse(part.has("thoughtSignature"));
        assertFalse(part.path("functionCall").has("id"));
        assertFalse(contents.get(2).path("parts").get(0).path("functionResponse").has("id"));
        model.complete(new ChatRequest(List.of(ChatMessage.user("read"),
                ChatMessage.assistantToolCalls("", List.of(call), first.providerMetadata()), ChatMessage.toolResult(call, "source")), List.of(), 100));
        var replayedPart = requests.get(2).path("contents").get(1).path("parts").get(0);
        assertFalse(replayedPart.has("thoughtSignature"));
        assertFalse(replayedPart.path("functionCall").has("id"));
    }

    @Test void metadataIsImmutableAndOldSerializedRecordsRemainReadable() throws Exception {
        var metadata = new HashMap<>(Map.of(GeminiProvider.THOUGHT_SIGNATURE, "opaque"));
        var call = new ToolCall("a", "read_file", "{}", metadata);
        metadata.clear();
        assertEquals("opaque", call.providerMetadata().get(GeminiProvider.THOUGHT_SIGNATURE));
        assertThrows(UnsupportedOperationException.class, () -> call.providerMetadata().put("x", "y"));
        assertEquals(call, AbstractHttpChatModel.JSON.readValue(AbstractHttpChatModel.JSON.writeValueAsString(call), ToolCall.class));
        var old = AbstractHttpChatModel.JSON.readValue("{\"id\":\"a\",\"name\":\"read_file\",\"argumentsJson\":\"{}\"}", ToolCall.class);
        assertEquals(new ToolCall("a", "read_file", "{}"), old);
        assertTrue(old.providerMetadata().isEmpty());
    }

    @Test void rejectsMalformedArgumentsAndUnknownOrMissingFinishReasons() throws Exception {
        for (String response : List.of(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read_file\",\"args\":[]}}]},\"finishReason\":\"STOP\"}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"MALFORMED_FUNCTION_CALL\"}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]}}]}")) {
            var model = model(response);
            assertThrows(ProviderException.class, () -> model.complete(request()));
            server.stop(0);
        }
    }

    private ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("read")), List.of(), 100); }

    private dev.codeharness.core.model.ChatModel model(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/test:generateContent", exchange -> {
            requests.add(AbstractHttpChatModel.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GeminiProvider().create(new ProviderConfig("gemini", "test",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"), null));
    }
}
