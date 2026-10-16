package dev.codeharness.providers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 暴露工具调用前，验证一轮完整的原生 Chat Completions SSE 响应。 */
final class OpenAiChatStream {
    private static final ObjectReader STRICT = AbstractHttpChatModel.JSON.reader()
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int TEXT_LIMIT = 1_048_576;
    private static final int ARGUMENT_LIMIT = 4_194_304;
    private final Consumer<String> observer;
    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, Call> calls = new TreeMap<>();
    private int argumentChars;
    private FinishReason finish;
    private boolean done;
    private boolean refused;
    private TokenUsage usage = TokenUsage.ZERO;
    private java.util.Map<String, String> usageMetadata = UsageReporting.metadata(false, false, false);

    OpenAiChatStream(Consumer<String> observer) { this.observer = observer == null ? ignored -> {} : observer; }

    boolean onEvent(String event) {
        if (done) throw failure("在 DONE 之后仍含数据");
        if ("[DONE]".equals(event.strip())) {
            if (finish == null) throw failure("结束时没有结束原因");
            done = true;
            return false;
        }
        JsonNode chunk = parse(event);
        if (!chunk.isObject() || chunk.hasNonNull("error") || !chunk.path("choices").isArray()) {
            throw failure("分块结构无效");
        }
        if (chunk.hasNonNull("usage")) {
            JsonNode counts = chunk.get("usage");
            if (!counts.isObject()) throw failure("用量数据无效");
            JsonNode details = counts.path("prompt_tokens_details");
            if (!details.isMissingNode() && !details.isNull() && !details.isObject()) throw failure("用量数据无效");
            usage = new TokenUsage(count(counts, "prompt_tokens"), count(counts, "completion_tokens"), count(details, "cached_tokens"));
            usageMetadata = UsageReporting.metadata(UsageReporting.reported(counts, "prompt_tokens"),
                    UsageReporting.reported(counts, "completion_tokens"), UsageReporting.reported(details, "cached_tokens"));
        }
        var seen = new HashSet<Integer>();
        for (JsonNode choice : chunk.path("choices")) {
            int index = index(choice, "index", Integer.MAX_VALUE);
            if (!seen.add(index) || index != 0) throw failure("包含不受支持的候选索引");
            if (finish != null) throw failure("完成之后仍包含候选结果");
            JsonNode delta = choice.path("delta");
            if (!delta.isObject()) throw failure("增量内容格式错误");
            if (delta.hasNonNull("role") && !"assistant".equals(string(delta, "role"))) throw failure("角色无效");
            if (delta.hasNonNull("function_call")) throw failure("使用了不受支持的旧式函数调用");
            if (delta.hasNonNull("content")) {
                String value = string(delta, "content");
                append(text, value, TEXT_LIMIT);
                if (!value.isEmpty()) {
                    try { observer.accept(value); } catch (RuntimeException ignored) { /* 进度通知不能使轮次失败。 */ }
                }
            }
            if (delta.hasNonNull("refusal")) {
                string(delta, "refusal");
                refused = true;
            }
            if (delta.hasNonNull("tool_calls")) {
                if (!delta.path("tool_calls").isArray()) throw failure("函数片段格式错误");
                var fragmentIndices = new HashSet<Integer>();
                for (JsonNode fragment : delta.get("tool_calls")) {
                    int callIndex = index(fragment, "index", 127);
                    if (!fragmentIndices.add(callIndex)) throw failure("函数索引重复");
                    Call call = calls.computeIfAbsent(callIndex, ignored -> new Call());
                    if (fragment.hasNonNull("type")) {
                        if (!"function".equals(string(fragment, "type"))) throw failure("工具类型不受支持");
                        call.functionType = true;
                    }
                    if (fragment.hasNonNull("id")) append(call.id, string(fragment, "id"), 4096);
                    if (fragment.hasNonNull("function")) {
                        JsonNode function = fragment.get("function");
                        if (!function.isObject()) throw failure("函数片段格式错误");
                        if (function.hasNonNull("name")) append(call.name, string(function, "name"), 4096);
                        if (function.hasNonNull("arguments")) {
                            String arguments = string(function, "arguments");
                            if (arguments.length() > ARGUMENT_LIMIT - argumentChars) throw failure("超过参数大小限制");
                            argumentChars += arguments.length();
                            call.arguments.append(arguments);
                        }
                    }
                }
            }
            if (choice.hasNonNull("finish_reason")) {
                finish = switch (string(choice, "finish_reason")) {
                    case "stop" -> FinishReason.STOP;
                    case "tool_calls" -> FinishReason.TOOL_CALLS;
                    case "length" -> FinishReason.LENGTH;
                    case "content_filter" -> FinishReason.CONTENT_FILTER;
                    default -> throw failure("结束原因不受支持");
                };
            }
        }
        return true;
    }

    ChatResponse response() {
        if (!done || finish == null) throw failure("在确认完成之前结束");
        if (refused || finish == FinishReason.CONTENT_FILTER) {
            return new ChatResponse(text.toString(), java.util.List.of(), usage, FinishReason.CONTENT_FILTER, usageMetadata);
        }
        if (finish == FinishReason.LENGTH) return new ChatResponse(text.toString(), java.util.List.of(), usage, finish, usageMetadata);
        var completedCalls = new ArrayList<ToolCall>();
        var ids = new HashSet<String>();
        int expectedIndex = 0;
        for (var entry : calls.entrySet()) {
            Call call = entry.getValue();
            if (entry.getKey() != expectedIndex++ || !call.functionType || call.id.toString().isBlank()
                    || call.name.toString().isBlank() || !ids.add(call.id.toString())) {
                throw failure("已完成的函数调用无效");
            }
            JsonNode arguments = parse(call.arguments.toString());
            if (!arguments.isObject()) throw failure("函数参数必须是 JSON 对象");
            completedCalls.add(new ToolCall(call.id.toString(), call.name.toString(), call.arguments.toString()));
        }
        if (finish == FinishReason.TOOL_CALLS && completedCalls.isEmpty()) throw failure("遗漏已完成的函数调用");
        if (!completedCalls.isEmpty()) finish = FinishReason.TOOL_CALLS;
        return new ChatResponse(text.toString(), completedCalls, usage, finish, usageMetadata);
    }

    static JsonNode parse(String value) {
        try {
            JsonNode parsed = STRICT.readTree(value);
            if (parsed == null) throw failure("包含空 JSON");
            return parsed;
        } catch (java.io.IOException invalid) { throw failure("包含格式错误的 JSON"); }
    }

    private static int index(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!node.isObject() || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > maximum) {
            throw failure("索引无效");
        }
        return value.intValue();
    }

    private static String string(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw failure("文本字段格式错误");
        return node.get(field).textValue();
    }

    private static long count(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw failure("用量数据无效");
        return value.longValue();
    }

    private static void append(StringBuilder target, String fragment, int limit) {
        if (fragment.length() > limit - target.length()) throw failure("累计字段超过大小限制");
        target.append(fragment);
    }

    private static ProviderException failure(String message) { return new ProviderException("Chat 流：" + message); }
    private static final class Call {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
        boolean functionType;
    }
}
