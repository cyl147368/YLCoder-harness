package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Anthropic Messages API 适配器。 */
public final class AnthropicProvider implements ModelProvider {
    @Override public String id() { return "anthropic"; }

    @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId())) throw new IllegalArgumentException("不支持的厂商 ID：" + config.providerId());
        return new Model(config);
    }

    private static final class Model extends AbstractHttpChatModel {
        Model(ProviderConfig config) { super(config); }
        @Override protected URI defaultBaseUri() { return URI.create("https://api.anthropic.com/v1"); }
        @Override protected URI endpoint() { return resolve("/messages"); }

        @Override protected Map<String, String> protocolHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("anthropic-version", "2023-06-01");
            if (config.apiKey() != null && !config.apiKey().isBlank()) headers.put("x-api-key", config.apiKey());
            return headers;
        }

        @Override protected ObjectNode encode(ChatRequest request) {
            ObjectNode body = JSON.createObjectNode().put("model", config.model());
            body.put("max_tokens", request.maxOutputTokens() == null ? 4096 : request.maxOutputTokens());
            ArrayNode system = JSON.createArrayNode();
            ArrayNode messages = body.putArray("messages");
            for (ChatMessage message : request.messages()) {
                if (message.role() == ChatMessage.Role.SYSTEM) {
                    if (!message.content().isBlank()) system.addObject().put("type", "text").put("text", message.content());
                    continue;
                }
                if (message.role() == ChatMessage.Role.TOOL) {
                    ObjectNode previous = messages.isEmpty() ? null : (ObjectNode) messages.get(messages.size() - 1);
                    ArrayNode results = previous != null && "user".equals(previous.path("role").asText())
                            ? (ArrayNode) previous.get("content") : messages.addObject().put("role", "user").putArray("content");
                    results.addObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.toolCallId())
                            .put("content", message.content());
                    continue;
                }
                ObjectNode item = messages.addObject().put("role", message.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user");
                ArrayNode content = item.putArray("content");
                if (!message.content().isEmpty()) content.addObject().put("type", "text").put("text", message.content());
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode block = content.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name());
                    block.set("input", parseObject(call.argumentsJson()));
                }
                if (content.isEmpty()) content.addObject().put("type", "text").put("text", "");
            }
            if (!system.isEmpty()) body.set("system", system);
            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (ToolDefinition definition : request.tools()) {
                    ObjectNode tool = tools.addObject().put("name", definition.name()).put("description", definition.description());
                    tool.set("input_schema", JSON.valueToTree(definition.inputSchema()));
                }
            }
            return body;
        }

        @Override protected ChatResponse decode(JsonNode payload) {
            if (!payload.path("content").isArray()) throw new ProviderException("Anthropic 响应缺少 content 数组");
            StringBuilder text = new StringBuilder();
            java.util.List<ToolCall> calls = new java.util.ArrayList<>();
            for (JsonNode block : payload.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.add(new ToolCall(block.path("id").asText("call-" + (calls.size() + 1)),
                            block.path("name").asText(), stringify(block.path("input"))));
                }
            }
            JsonNode usage = payload.path("usage");
            long cached = usage.path("cache_read_input_tokens").asLong(0);
            long input = usage.path("input_tokens").asLong(0) + cached + usage.path("cache_creation_input_tokens").asLong(0);
            TokenUsage counts = new TokenUsage(input, usage.path("output_tokens").asLong(0), cached);
            JsonNode stopMarker = payload.path("stop_reason");
            if (!stopMarker.isTextual() || stopMarker.asText().isBlank()) {
                throw new ProviderException("Anthropic 响应缺少有效的停止原因");
            }
            String reason = stopMarker.asText();
            FinishReason finish = switch (reason) {
                case "tool_use" -> FinishReason.TOOL_CALLS;
                case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
                case "refusal" -> FinishReason.CONTENT_FILTER;
                case "end_turn", "stop_sequence" -> FinishReason.STOP;
                default -> throw new ProviderException("Anthropic 响应的停止原因不受支持");
            };
            if (!calls.isEmpty() && finish == FinishReason.STOP) finish = FinishReason.TOOL_CALLS;
            if (finish == FinishReason.TOOL_CALLS && calls.isEmpty()) throw new ProviderException("Anthropic 响应遗漏了工具调用");
            return new ChatResponse(text.toString(), calls, counts, finish,
                    UsageReporting.metadata(UsageReporting.reported(usage, "input_tokens")
                                    && UsageReporting.optionalCountValid(usage, "cache_read_input_tokens")
                                    && UsageReporting.optionalCountValid(usage, "cache_creation_input_tokens"),
                            UsageReporting.reported(usage, "output_tokens"),
                            UsageReporting.reported(usage, "cache_read_input_tokens")));
        }

        private ObjectNode parseObject(String value) {
            try {
                JsonNode parsed = JSON.readTree(value == null || value.isBlank() ? "{}" : value);
                if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
                return (ObjectNode) parsed;
            } catch (Exception e) {
                throw new IllegalArgumentException("工具参数必须是合法 JSON", e);
            }
        }

        private String stringify(JsonNode value) {
            try { return JSON.writeValueAsString(value); }
            catch (Exception e) { throw new IllegalArgumentException("无法编码 Anthropic 工具参数", e); }
        }
    }
}
