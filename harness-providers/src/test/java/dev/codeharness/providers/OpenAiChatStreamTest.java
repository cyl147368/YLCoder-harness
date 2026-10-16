package dev.codeharness.providers;

import dev.codeharness.core.model.FinishReason;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatStreamTest {
    @Test void emitsVisibleTextAndReassemblesParallelArgumentsOnlyAfterDone() {
        var deltas = new ArrayList<String>();
        var parser = new OpenAiChatStream(deltas::add);
        parser.onEvent(chunk("{\"role\":\"assistant\",\"content\":\"Read \"}", null));
        parser.onEvent(chunk("""
                {"content":"files","tool_calls":[
                  {"index":1,"type":"function","id":"call_b","function":{"name":"read_file","arguments":"{"}},
                  {"index":0,"type":"function","id":"call_a","function":{"name":"read_file","arguments":"{"}}]}
                """, null));
        parser.onEvent(chunk("""
                {"tool_calls":[
                  {"index":0,"function":{"arguments":"\\\"path\\\":\\\"a\\\"}"}},
                  {"index":1,"function":{"arguments":"\\\"path\\\":\\\"b\\\"}"}}]}
                """, "tool_calls"));
        assertThrows(ProviderException.class, parser::response);
        parser.onEvent("{\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":8}}}");
        assertFalse(parser.onEvent("[DONE]"));
        var response = parser.response();
        assertEquals(List.of("Read ", "files"), deltas);
        assertEquals("Read files", response.text());
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(List.of("call_a", "call_b"), response.toolCalls().stream().map(call -> call.id()).toList());
        assertEquals("{\"path\":\"b\"}", response.toolCalls().get(1).argumentsJson());
        assertEquals(20, response.usage().inputTokens());
        assertEquals(8, response.usage().cachedInputTokens());
    }

    @Test void failedCompletionNeverExposesPartialCalls() {
        for (String reason : List.of("length", "content_filter")) {
            var parser = new OpenAiChatStream(null);
            parser.onEvent(chunk("{\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"id\":\"call_a\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{\"}}]}", reason));
            parser.onEvent("[DONE]");
            assertTrue(parser.response().toolCalls().isEmpty());
            assertEquals(reason.equals("length") ? FinishReason.LENGTH : FinishReason.CONTENT_FILTER, parser.response().finishReason());
        }
    }

    @Test void rejectsMalformedFragmentAndEnvelopeWithoutLeakingPayload() {
        for (String event : List.of("{}", "{\"choices\":[],\"error\":{\"message\":\"secret\"}}", "{\"choices\":[],\"choices\":[]}",
                chunk("{\"content\":42}", null), chunk("{\"function_call\":{}}", null),
                chunk("{\"tool_calls\":[{\"index\":128}]}", null), chunk("{\"tool_calls\":[{\"index\":-1}]}", null),
                chunk("{\"tool_calls\":[{\"index\":0},{\"index\":0}]}", null),
                chunk("{\"tool_calls\":[{\"index\":0,\"type\":\"computer\"}]}", null),
                chunk("{}", "secret"), "{\"choices\":[],\"usage\":{\"prompt_tokens\":-1}}", "{\"choices\":[]} {}")) {
            var failure = assertThrows(ProviderException.class, () -> new OpenAiChatStream(null).onEvent(event));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test void rejectsMissingCompletionAndStrictlyValidatesFinalCalls() {
        var unfinished = new OpenAiChatStream(null);
        assertThrows(ProviderException.class, () -> unfinished.onEvent("[DONE]"));
        for (String args : List.of("[]", "{\"x\":1,\"x\":2}", "{} {}")) {
            var parser = new OpenAiChatStream(null);
            var delta = AbstractHttpChatModel.JSON.createObjectNode();
            delta.putArray("tool_calls").addObject().put("index", 0).put("type", "function").put("id", "call_a")
                    .putObject("function").put("name", "read").put("arguments", args);
            parser.onEvent(chunk(delta.toString(), "tool_calls"));
            parser.onEvent("[DONE]");
            assertThrows(ProviderException.class, parser::response);
        }
    }

    @Test void observerFailureIsIsolatedAndTextIsBounded() {
        var parser = new OpenAiChatStream(delta -> { throw new IllegalStateException("observer secret"); });
        parser.onEvent(chunk("{\"content\":\"done\"}", "stop"));
        parser.onEvent("[DONE]");
        assertEquals("done", parser.response().text());
        var oversized = new OpenAiChatStream(null);
        assertThrows(ProviderException.class, () -> oversized.onEvent(chunk("{\"content\":\"" + "x".repeat(1_048_577) + "\"}", null)));
    }

    private static String chunk(String delta, String finish) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finish == null ? "null" : "\"" + finish + "\"") + "}]}";
    }
}
