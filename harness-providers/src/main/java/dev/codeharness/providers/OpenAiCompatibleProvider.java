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

/** OpenAI Chat Completions 适配器，也支持 Ollama 及其他兼容服务。 */
public final class OpenAiCompatibleProvider implements ModelProvider {
    @Override public String id() { return "openai-compatible"; }

    @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId()) && !"openai".equals(config.providerId()) && !"ollama".equals(config.providerId())) {
            throw new IllegalArgumentException("不支持的厂商 ID：" + config.providerId());
        }
        return new Model(config);
    }

    static class Model extends AbstractHttpChatModel {
        Model(ProviderConfig config) { super(config); }

        @Override public ChatResponse complete(ChatRequest request, java.util.function.Consumer<String> onTextDelta) {
            try {
                ObjectNode body = encode(request).put("stream", true);
                if ("openai".equals(config.providerId()) || "azure-openai".equals(config.providerId())) {
                    body.putObject("stream_options").put("include_usage", true);
                }
                var builder = java.net.http.HttpRequest.newBuilder(endpoint()).timeout(config.timeout())
                        .setHeader("Content-Type", "application/json").setHeader("Accept", "text/event-stream, application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
                protocolHeaders().forEach(builder::setHeader);
                config.headers().forEach(builder::setHeader);
                var stream = new OpenAiChatStream(onTextDelta);
                String fallback = NativeStreamTransport.exchange(client, builder.build(), config.timeout(), stream::onEvent);
                if (fallback == null) return stream.response();
                JsonNode payload = OpenAiChatStream.parse(fallback);
                if (!payload.isObject() || payload.hasNonNull("error")) throw new ProviderException("Chat 流返回了无效的响应结构");
                return decode(payload);
            } catch (ProviderException failure) {
                throw failure;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new ProviderException("Chat 流式请求失败");
            }
        }

        @Override protected URI defaultBaseUri() {
            return switch (config.providerId()) {
                case "openai" -> URI.create("https://api.openai.com/v1");
                case "ollama" -> URI.create("http://localhost:11434/v1");
                default -> throw new IllegalArgumentException("openai-compatible 厂商必须配置 baseUri");
            };
        }

        @Override protected URI endpoint() { return resolve("/chat/completions"); }

        @Override protected Map<String, String> protocolHeaders() {
            if (config.apiKey() == null || config.apiKey().isBlank()) return Map.of();
            return Map.of("Authorization", "Bearer " + config.apiKey());
        }

        @Override protected ObjectNode encode(ChatRequest request) {
            ObjectNode body = JSON.createObjectNode().put("model", config.model());
            ArrayNode messages = body.putArray("messages");
            for (ChatMessage message : request.messages()) {
                ObjectNode item = messages.addObject();
                item.put("role", switch (message.role()) {
                    case SYSTEM -> "system"; case USER -> "user"; case ASSISTANT -> "assistant"; case TOOL -> "tool";
                });
                item.put("content", message.content());
                if (message.role() == ChatMessage.Role.TOOL && message.toolName() != null) item.put("name", message.toolName());
                if (message.role() == ChatMessage.Role.TOOL && message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = item.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode fn = calls.addObject().put("id", call.id()).put("type", "function").putObject("function");
                        fn.put("name", call.name()).put("arguments", call.argumentsJson());
                    }
                }
            }
            if (request.maxOutputTokens() != null) {
                String limitField = "openai".equals(config.providerId()) || "azure-openai".equals(config.providerId())
                        ? "max_completion_tokens" : "max_tokens";
                body.put(limitField, request.maxOutputTokens());
            }
            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (ToolDefinition definition : request.tools()) {
                    ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                    function.put("name", definition.name()).put("description", definition.description());
                    function.set("parameters", JSON.valueToTree(definition.inputSchema()));
                }
            }
            return body;
        }

        @Override protected ChatResponse decode(JsonNode payload) {
            JsonNode choice = at(payload, "choices", "0");
            if (choice == null || !choice.isObject() || !choice.path("message").isObject()) {
                throw new ProviderException("OpenAI 兼容响应缺少 assistant 消息");
            }
            JsonNode message = choice.path("message");
            java.util.List<ToolCall> calls = new java.util.ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            for (JsonNode call : toolCalls) {
                calls.add(new ToolCall(text(call.path("id"), "call-" + (calls.size() + 1)),
                        text(at(call, "function", "name"), ""), text(at(call, "function", "arguments"), "{}")));
            }
            JsonNode usage = payload.path("usage");
            long input = usage.path("prompt_tokens").asLong(0);
            long output = usage.path("completion_tokens").asLong(0);
            long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
            JsonNode finishMarker = choice.path("finish_reason");
            if (!finishMarker.isTextual() || finishMarker.asText().isBlank()) {
                throw new ProviderException("OpenAI 兼容响应缺少有效的结束原因");
            }
            String reason = finishMarker.asText();
            FinishReason finish = switch (reason) {
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                case "length" -> FinishReason.LENGTH;
                case "tool_calls" -> FinishReason.TOOL_CALLS;
                case "stop" -> FinishReason.STOP;
                default -> throw new ProviderException("OpenAI 兼容响应的结束原因不受支持");
            };
            if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) finish = FinishReason.CONTENT_FILTER;
            if (!calls.isEmpty() && finish == FinishReason.STOP) finish = FinishReason.TOOL_CALLS;
            if (finish == FinishReason.TOOL_CALLS && calls.isEmpty()) throw new ProviderException("OpenAI 兼容响应遗漏了工具调用");
            return new ChatResponse(text(message.path("content"), ""), calls, new TokenUsage(input, output, cached), finish,
                    UsageReporting.metadata(UsageReporting.reported(usage, "prompt_tokens"),
                            UsageReporting.reported(usage, "completion_tokens"),
                            UsageReporting.reported(usage.path("prompt_tokens_details"), "cached_tokens")));
        }
    }
}
