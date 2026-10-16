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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Google Gemini generateContent API 适配器，支持 API 密钥或自定义网关地址。 */
public final class GeminiProvider implements ModelProvider {
    private static final com.fasterxml.jackson.databind.ObjectMapper STRICT_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final String FUNCTION_CALL_ID = "gemini.functionCallId";
    static final String THOUGHT_SIGNATURE = "gemini.thoughtSignature";
    static final String CONTENT_PARTS = "gemini.contentParts";
    @Override public String id() { return "gemini"; }

    @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId())) throw new IllegalArgumentException("不支持的厂商 ID：" + config.providerId());
        return new Model(config);
    }

    private static final class Model extends AbstractHttpChatModel {
        Model(ProviderConfig config) { super(config); }
        @Override protected URI defaultBaseUri() { return URI.create("https://generativelanguage.googleapis.com/v1beta"); }
        @Override protected URI endpoint() {
            return resolve("/models/" + URLEncoder.encode(config.model(), StandardCharsets.UTF_8).replace("+", "%20") + ":generateContent");
        }

        @Override protected Map<String, String> protocolHeaders() {
            return config.apiKey() == null || config.apiKey().isBlank() ? Map.of() : Map.of("x-goog-api-key", config.apiKey());
        }

        @Override protected ObjectNode encode(ChatRequest request) {
            ObjectNode body = JSON.createObjectNode();
            ArrayNode systemParts = JSON.createArrayNode();
            ArrayNode contents = body.putArray("contents");
            Map<String, ToolCall> precedingCalls = new LinkedHashMap<>();
            for (ChatMessage message : request.messages()) {
                String replay = message.providerMetadata().get(CONTENT_PARTS);
                if (replay != null && message.role() != ChatMessage.Role.ASSISTANT) {
                    throw new ProviderException("Gemini 回放元数据必须属于 assistant 消息");
                }
                if (message.role() == ChatMessage.Role.SYSTEM) {
                    if (!message.content().isBlank()) systemParts.addObject().put("text", message.content());
                    continue;
                }
                if (message.role() == ChatMessage.Role.TOOL) {
                    ObjectNode previous = contents.isEmpty() ? null : (ObjectNode) contents.get(contents.size() - 1);
                    ArrayNode resultParts = previous != null && "user".equals(previous.path("role").asText())
                            ? (ArrayNode) previous.get("parts") : contents.addObject().put("role", "user").putArray("parts");
                    ObjectNode part = resultParts.addObject().putObject("functionResponse");
                    part.put("name", message.toolName() == null ? "tool" : message.toolName());
                    ToolCall original = precedingCalls.get(message.toolCallId());
                    if (original != null && original.providerMetadata().containsKey(FUNCTION_CALL_ID)) {
                        part.put("id", original.providerMetadata().get(FUNCTION_CALL_ID));
                    }
                    part.set("response", toolResult(message.content()));
                    continue;
                }
                ObjectNode item = contents.addObject().put("role", message.role() == ChatMessage.Role.ASSISTANT ? "model" : "user");
                if (replay != null) {
                    item.set("parts", replayParts(replay, message));
                    for (ToolCall call : message.toolCalls()) precedingCalls.put(call.id(), call);
                    continue;
                }
                ArrayNode parts = item.putArray("parts");
                if (!message.content().isEmpty()) parts.addObject().put("text", message.content());
                for (ToolCall call : message.toolCalls()) {
                    validateLegacyMetadata(call);
                    precedingCalls.put(call.id(), call);
                    ObjectNode functionPart = parts.addObject();
                    ObjectNode function = functionPart.putObject("functionCall");
                    function.put("name", call.name());
                    function.set("args", parseObject(call.argumentsJson()));
                    if (call.providerMetadata().containsKey(FUNCTION_CALL_ID)) {
                        function.put("id", call.providerMetadata().get(FUNCTION_CALL_ID));
                    }
                    if (call.providerMetadata().containsKey(THOUGHT_SIGNATURE)) {
                        functionPart.put("thoughtSignature", call.providerMetadata().get(THOUGHT_SIGNATURE));
                    }
                }
                if (parts.isEmpty()) parts.addObject().put("text", "");
            }
            if (!systemParts.isEmpty()) body.putObject("systemInstruction").set("parts", systemParts);
            ObjectNode generation = body.putObject("generationConfig");
            if (request.maxOutputTokens() != null) generation.put("maxOutputTokens", request.maxOutputTokens());
            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                ArrayNode declarations = tools.addObject().putArray("functionDeclarations");
                for (ToolDefinition definition : request.tools()) {
                    ObjectNode declaration = declarations.addObject();
                    declaration.put("name", definition.name()).put("description", definition.description());
                    declaration.set("parameters", JSON.valueToTree(definition.inputSchema()));
                }
            }
            return body;
        }

        @Override protected ChatResponse decode(JsonNode payload) {
            JsonNode candidate = at(payload, "candidates", "0");
            if (candidate == null || !candidate.isObject()) {
                if (payload.path("promptFeedback").hasNonNull("blockReason")) {
                    return new ChatResponse("", java.util.List.of(), TokenUsage.ZERO, FinishReason.CONTENT_FILTER,
                            UsageReporting.metadata(false, false, false));
                }
                throw new ProviderException("Gemini 响应缺少候选结果");
            }
            JsonNode rawParts = candidate.path("content").path("parts");
            JsonNode usage = payload.path("usageMetadata");
            TokenUsage counts = new TokenUsage(usage.path("promptTokenCount").asLong(0), usage.path("candidatesTokenCount").asLong(0),
                    usage.path("cachedContentTokenCount").asLong(0));
            Map<String, String> usageMetadata = UsageReporting.metadata(UsageReporting.reported(usage, "promptTokenCount"),
                    UsageReporting.reported(usage, "candidatesTokenCount"), UsageReporting.reported(usage, "cachedContentTokenCount"));
            String reason = candidate.path("finishReason").asText("");
            FinishReason finish = switch (reason) {
                case "MAX_TOKENS" -> FinishReason.LENGTH;
                case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> FinishReason.CONTENT_FILTER;
                case "STOP" -> FinishReason.STOP;
                default -> throw new ProviderException("Gemini 响应的结束原因不受支持");
            };
            if (finish == FinishReason.STOP && !rawParts.isArray()) {
                throw new ProviderException("Gemini 响应缺少内容片段");
            }
            // 不完整或遭拒绝的函数调用不能成为可执行或可回放证据。
            if (finish != FinishReason.STOP) return new ChatResponse("", List.of(), counts, finish, usageMetadata);
            ArrayNode parts = (ArrayNode) rawParts;
            Parsed parsed = parseParts(parts);
            if (parsed.text().isBlank() && parsed.calls().isEmpty()) {
                throw new ProviderException("Gemini 已完成但没有可见文本或函数调用");
            }
            if (!parsed.calls().isEmpty()) finish = FinishReason.TOOL_CALLS;
            var metadata = new LinkedHashMap<>(usageMetadata);
            metadata.put(CONTENT_PARTS, parts.toString());
            return new ChatResponse(parsed.text(), parsed.calls(), counts, finish, metadata);
        }

        private static Parsed parseParts(ArrayNode parts) {
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            Set<String> wireIds = new HashSet<>();
            for (JsonNode part : parts) {
                requireFields(part, Set.of("text", "functionCall", "thought", "thoughtSignature"));
                boolean hasText = part.has("text"), hasCall = part.has("functionCall");
                if (hasText == hasCall) throw new ProviderException("Gemini 内容片段没有唯一受支持的值");
                if (part.has("thought") && !part.get("thought").isBoolean()) {
                    throw new ProviderException("Gemini 思考标记格式错误");
                }
                String signature = optionalText(part, "thoughtSignature");
                if (hasText) {
                    if (!part.get("text").isTextual()) throw new ProviderException("Gemini 文本片段格式错误");
                    if (!part.path("thought").asBoolean(false)) text.append(part.get("text").asText());
                    continue;
                }
                if (part.path("thought").asBoolean(false)) throw new ProviderException("Gemini 函数调用被标记为思考内容");
                JsonNode function = part.get("functionCall");
                requireFields(function, Set.of("name", "args", "id"));
                if (!function.path("name").isTextual() || function.path("name").asText().isBlank()) {
                    throw new ProviderException("Gemini 函数调用缺少有效名称");
                }
                JsonNode arguments = function.has("args") ? function.get("args") : JSON.createObjectNode();
                if (!arguments.isObject()) throw new ProviderException("Gemini 函数参数必须是 JSON 对象");
                String wireId = optionalText(function, "id");
                if (wireId != null && !wireIds.add(wireId)) throw new ProviderException("Gemini 返回了重复的函数调用 ID");
                Map<String, String> metadata = new LinkedHashMap<>();
                if (wireId != null) metadata.put(FUNCTION_CALL_ID, wireId);
                if (signature != null) metadata.put(THOUGHT_SIGNATURE, signature);
                calls.add(new ToolCall(wireId == null ? "gemini-call-" + java.util.UUID.randomUUID() : wireId,
                        function.get("name").asText(), arguments.toString(), metadata));
            }
            return new Parsed(text.toString(), List.copyOf(calls));
        }

        private static ArrayNode replayParts(String serialized, ChatMessage message) {
            try {
                JsonNode node = STRICT_JSON.readTree(serialized);
                if (!(node instanceof ArrayNode parts)) throw new ProviderException("Gemini 回放元数据格式错误");
                Parsed parsed = parseParts(parts);
                if (!parsed.text().equals(message.content()) || parsed.calls().size() != message.toolCalls().size()) {
                    throw new ProviderException("Gemini 回放与消息历史不一致");
                }
                for (int i = 0; i < parsed.calls().size(); i++) {
                    ToolCall actual = parsed.calls().get(i), expected = message.toolCalls().get(i);
                    validateLegacyMetadata(expected);
                    if (!actual.name().equals(expected.name())
                            || !parseObject(actual.argumentsJson()).equals(parseObject(expected.argumentsJson()))
                            || !java.util.Objects.equals(actual.providerMetadata().get(FUNCTION_CALL_ID), expected.providerMetadata().get(FUNCTION_CALL_ID))
                            || !java.util.Objects.equals(actual.providerMetadata().get(THOUGHT_SIGNATURE), expected.providerMetadata().get(THOUGHT_SIGNATURE))
                            || (actual.providerMetadata().containsKey(FUNCTION_CALL_ID) && !actual.id().equals(expected.id()))) {
                        throw new ProviderException("Gemini 回放与函数历史不一致");
                    }
                }
                if (parsed.text().isBlank() && parsed.calls().isEmpty()) {
                    throw new ProviderException("Gemini 回放没有可见文本或函数调用");
                }
                return parts;
            } catch (java.io.IOException malformed) {
                throw new ProviderException("Gemini 回放元数据格式错误");
            }
        }

        private static void requireFields(JsonNode node, Set<String> allowed) {
            if (!node.isObject()) throw new ProviderException("Gemini 内容片段格式错误");
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                if (!allowed.contains(fields.next())) throw new ProviderException("Gemini 内容片段类型不受支持");
            }
        }

        private record Parsed(String text, List<ToolCall> calls) {}

        private static void validateLegacyMetadata(ToolCall call) {
            String id = call.providerMetadata().get(FUNCTION_CALL_ID);
            String signature = call.providerMetadata().get(THOUGHT_SIGNATURE);
            if ((id != null && (id.isBlank() || !id.equals(call.id()))) || (signature != null && signature.isBlank())) {
                throw new ProviderException("Gemini 函数元数据相互矛盾");
            }
            if (call.providerMetadata().containsKey(CONTENT_PARTS)) {
                throw new ProviderException("Gemini parts 元数据必须属于 assistant 消息");
            }
        }

        private static String optionalText(JsonNode node, String field) {
            if (!node.has(field)) return null;
            JsonNode value = node.get(field);
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new ProviderException("Gemini 响应包含无效字段：" + field);
            }
            return value.asText();
        }

        private ObjectNode toolResult(String value) {
            try {
                JsonNode parsed = JSON.readTree(value);
                if (parsed != null && parsed.isObject()) return (ObjectNode) parsed;
            } catch (java.io.IOException ignored) {
                // 工作区工具通常返回文本而不是 JSON。
            }
            return JSON.createObjectNode().put("output", value);
        }

        private static ObjectNode parseObject(String value) {
            try {
                JsonNode parsed = STRICT_JSON.readTree(value);
                if (parsed == null || !parsed.isObject()) throw new ProviderException("Gemini 工具参数必须是 JSON 对象");
                return (ObjectNode) parsed;
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                throw new ProviderException("Gemini 工具参数必须是合法 JSON");
            }
        }
    }
}
