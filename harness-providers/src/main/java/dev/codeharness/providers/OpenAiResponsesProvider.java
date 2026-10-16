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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 无状态 OpenAI Responses 适配器，支持文本与本地函数工具。 */
public final class OpenAiResponsesProvider implements ModelProvider {
    static final String OUTPUT_ITEMS = "openai.responses.outputItems";

    @Override public String id() { return "openai-responses"; }

    @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId())) throw new IllegalArgumentException("厂商 ID 不受支持");
        return new Model(config);
    }

    private static final class Model extends AbstractHttpChatModel {
        private static final com.fasterxml.jackson.databind.ObjectReader STRICT_JSON = JSON.reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        Model(ProviderConfig config) { super(config); }
        @Override protected URI defaultBaseUri() { return URI.create("https://api.openai.com/v1"); }
        @Override protected URI endpoint() { return resolve("/responses"); }
        @Override protected Map<String, String> protocolHeaders() {
            return config.apiKey() == null || config.apiKey().isBlank() ? Map.of()
                    : Map.of("Authorization", "Bearer " + config.apiKey());
        }

        @Override protected ObjectNode encode(ChatRequest request) {
            ObjectNode body = JSON.createObjectNode().put("model", config.model()).put("store", false);
            body.putArray("include").add("reasoning.encrypted_content");
            if (request.maxOutputTokens() != null) body.put("max_output_tokens", request.maxOutputTokens());
            ArrayNode input = body.putArray("input");
            var replayedIds = new HashSet<String>();
            for (ChatMessage message : request.messages()) {
                String replay = message.providerMetadata().get(OUTPUT_ITEMS);
                String legacyReplay = null;
                for (int i = 0; i < message.toolCalls().size(); i++) {
                    String value = message.toolCalls().get(i).providerMetadata().get(OUTPUT_ITEMS);
                    if (value == null) continue;
                    if (i != 0) throw new ProviderException("Responses 回放元数据重复");
                    legacyReplay = value;
                }
                if (replay != null || legacyReplay != null) {
                    if (message.role() != ChatMessage.Role.ASSISTANT) {
                        throw new ProviderException("Responses 回放元数据必须属于 assistant 消息");
                    }
                    ArrayNode output = replayItems(replay != null ? replay : legacyReplay, message);
                    if (replay != null && legacyReplay != null && !output.equals(replayItems(legacyReplay, message))) {
                        throw new ProviderException("Responses 回放元数据副本相互矛盾");
                    }
                    for (JsonNode item : output) {
                        if (item.hasNonNull("id") && !replayedIds.add(requiredText(item, "id"))) {
                            throw new ProviderException("Responses 回放输出 ID 重复");
                        }
                    }
                    input.addAll(output);
                } else if (message.role() == ChatMessage.Role.TOOL) {
                    input.addObject().put("type", "function_call_output")
                            .put("call_id", message.toolCallId()).put("output", message.content());
                } else if (!message.toolCalls().isEmpty()) {
                    if (!message.content().isEmpty()) addMessage(input, "assistant", message.content());
                    for (ToolCall call : message.toolCalls()) {
                        arguments(call.argumentsJson());
                        input.addObject().put("type", "function_call").put("call_id", call.id())
                                .put("name", call.name()).put("arguments", call.argumentsJson());
                    }
                } else {
                    addMessage(input, message.role().name().toLowerCase(java.util.Locale.ROOT), message.content());
                }
            }
            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (var definition : request.tools()) {
                    ObjectNode tool = tools.addObject().put("type", "function").put("name", definition.name())
                            .put("description", definition.description()).put("strict", false);
                    tool.set("parameters", JSON.valueToTree(definition.inputSchema()));
                }
            }
            return body;
        }

        private static void addMessage(ArrayNode input, String role, String content) {
            input.addObject().put("role", role).put("content", content);
        }

        @Override protected ChatResponse decode(JsonNode payload) {
            if (payload.hasNonNull("error")) throw new ProviderException("Responses 返回了错误");
            String status = requiredText(payload, "status");
            FinishReason finish;
            if ("completed".equals(status)) {
                if (payload.hasNonNull("incomplete_details")) throw new ProviderException("Responses 返回的完成详情相互矛盾");
                finish = FinishReason.STOP;
            } else if ("incomplete".equals(status)) {
                finish = switch (requiredText(payload.path("incomplete_details"), "reason")) {
                    case "max_output_tokens" -> FinishReason.LENGTH;
                    case "content_filter" -> FinishReason.CONTENT_FILTER;
                    default -> throw new ProviderException("Responses 返回了不受支持的未完成原因");
                };
            } else {
                throw new ProviderException("Responses 未成功完成");
            }
            if (!payload.path("output").isArray()) throw new ProviderException("Responses 遗漏了输出条目");
            ArrayNode output = (ArrayNode) payload.get("output");
            Parsed parsed = parseOutput(output, finish == FinishReason.STOP);
            List<ToolCall> calls = List.of();
            Map<String, String> metadata = Map.of();
            if (finish == FinishReason.STOP) {
                if (parsed.refusal()) finish = FinishReason.CONTENT_FILTER;
                else {
                    requireEncryptedReasoning(output);
                    if (!parsed.calls().isEmpty()) {
                        calls = parsed.calls();
                        finish = FinishReason.TOOL_CALLS;
                    } else if (parsed.text().isBlank()) {
                        throw new ProviderException("Responses 已完成但没有文本或函数调用");
                    }
                    metadata = Map.of(OUTPUT_ITEMS, output.toString());
                }
            }
            JsonNode usage = payload.path("usage");
            TokenUsage counts = new TokenUsage(count(usage, "input_tokens"), count(usage, "output_tokens"),
                    count(usage.path("input_tokens_details"), "cached_tokens"));
            return new ChatResponse(parsed.text(), calls, counts, finish,
                    UsageReporting.with(metadata, UsageReporting.reported(usage, "input_tokens"),
                            UsageReporting.reported(usage, "output_tokens"),
                            UsageReporting.reported(usage.path("input_tokens_details"), "cached_tokens")));
        }

        private static Parsed parseOutput(ArrayNode output, boolean complete) {
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            var ids = new HashSet<String>();
            var itemIds = new HashSet<String>();
            boolean refusal = false;
            for (JsonNode item : output) {
                if (!item.isObject()) throw new ProviderException("Responses 输出条目格式错误");
                String type = requiredText(item, "type");
                if (item.hasNonNull("id") && !itemIds.add(requiredText(item, "id"))) {
                    throw new ProviderException("Responses 返回了重复输出 ID");
                }
                if (item.hasNonNull("status")) {
                    String itemStatus = requiredText(item, "status");
                    if (complete && !"completed".equals(itemStatus)) throw new ProviderException("Responses 输出条目尚未完成");
                    if (!List.of("completed", "incomplete", "in_progress").contains(itemStatus)) {
                        throw new ProviderException("Responses 输出条目的状态不受支持");
                    }
                }
                switch (type) {
                    case "message" -> {
                        if (!"assistant".equals(requiredText(item, "role")) || !item.path("content").isArray()) {
                            throw new ProviderException("Responses assistant 消息格式错误");
                        }
                        for (JsonNode content : item.path("content")) {
                            switch (requiredText(content, "type")) {
                                case "output_text" -> text.append(string(content, "text"));
                                case "refusal" -> { text.append(string(content, "refusal")); refusal = true; }
                                default -> throw new ProviderException("Responses 输出内容类型不受支持");
                            }
                        }
                    }
                    case "function_call" -> {
                        // 即使部分调用看似完整，不完整的函数参数也绝不能进入工具。
                        if (!complete) continue;
                        String callId = requiredText(item, "call_id");
                        if (!ids.add(callId)) throw new ProviderException("Responses 返回了重复函数调用 ID");
                        String args = string(item, "arguments");
                        arguments(args);
                        calls.add(new ToolCall(callId, requiredText(item, "name"), args));
                    }
                    case "reasoning" -> {
                        requiredText(item, "id");
                        if (!item.path("summary").isArray()) throw new ProviderException("Responses 推理条目格式错误");
                    }
                    default -> throw new ProviderException("Responses 输出条目类型不受支持");
                }
            }
            return new Parsed(text.toString(), calls, refusal);
        }

        private static ArrayNode replayItems(String serialized, ChatMessage message) {
            try {
                JsonNode node = STRICT_JSON.readTree(serialized);
                if (!(node instanceof ArrayNode output)) throw new ProviderException("Responses 回放元数据格式错误");
                Parsed parsed = parseOutput(output, true);
                requireEncryptedReasoning(output);
                List<ToolCall> expected = message.toolCalls();
                if (!parsed.text().equals(message.content()) || (parsed.text().isBlank() && parsed.calls().isEmpty())) {
                    throw new ProviderException("Responses 回放文本与历史不一致");
                }
                if (parsed.refusal() || parsed.calls().size() != expected.size()) throw new ProviderException("Responses 回放调用与历史不一致");
                for (int i = 0; i < expected.size(); i++) {
                    ToolCall actual = parsed.calls().get(i), original = expected.get(i);
                    if (!actual.id().equals(original.id()) || !actual.name().equals(original.name())
                            || !arguments(actual.argumentsJson()).equals(arguments(original.argumentsJson()))) {
                        throw new ProviderException("Responses 回放调用与历史不一致");
                    }
                    if (i > 0 && original.providerMetadata().containsKey(OUTPUT_ITEMS)) throw new ProviderException("Responses 回放元数据重复");
                }
                return output;
            } catch (java.io.IOException invalid) {
                throw new ProviderException("Responses 回放元数据格式错误");
            }
        }

        private static void requireEncryptedReasoning(ArrayNode output) {
            for (JsonNode item : output) {
                if ("reasoning".equals(item.path("type").asText())) requiredText(item, "encrypted_content");
            }
        }

        private static JsonNode arguments(String value) {
            try {
                JsonNode parsed = STRICT_JSON.readTree(value);
                if (parsed == null || !parsed.isObject()) throw new ProviderException("Responses 函数参数必须是 JSON 对象");
                return parsed;
            } catch (java.io.IOException invalid) {
                throw new ProviderException("Responses 函数参数必须是合法 JSON");
            }
        }

        private static String string(JsonNode node, String field) {
            if (!node.path(field).isTextual()) throw new ProviderException("Responses 字段缺失或格式错误：" + field);
            return node.get(field).asText();
        }

        private static String requiredText(JsonNode node, String field) {
            String value = string(node, field);
            if (value.isBlank()) throw new ProviderException("Responses 字段为空：" + field);
            return value;
        }

        private static long count(JsonNode usage, String field) {
            JsonNode value = usage.path(field);
            if (value.isMissingNode() || value.isNull()) return 0;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
                throw new ProviderException("Responses token 用量格式错误");
            }
            return value.asLong();
        }

        private record Parsed(String text, List<ToolCall> calls, boolean refusal) {}
    }
}
