package dev.codeharness.providers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.codeharness.core.model.ChatResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Responses SSE 的文本进度投影；只有完整 completed 响应可产生最终工具调用。 */
final class OpenAiResponsesStream {
    private static final ObjectReader STRICT = AbstractHttpChatModel.JSON.reader()
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_TEXT_CHARS = 1_048_576;
    private static final int MAX_PARTS = 1024;
    private static final Set<String> PROGRESS = Set.of("response.queued", "response.created", "response.in_progress");
    private static final Set<String> METADATA = Set.of("response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done", "response.output_text.done",
            "response.output_text.annotation.added", "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.refusal.delta", "response.refusal.done", "response.reasoning_summary_part.added",
            "response.reasoning_summary_part.done", "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
            "response.reasoning_text.delta", "response.reasoning_text.done");
    private final Consumer<String> observer;
    private final ProviderErrors errors;
    private final Map<Part, TextPart> parts = new HashMap<>();
    private final StringBuilder emitted = new StringBuilder();
    private JsonNode completed;
    private String responseId;
    private long sequence = -1;

    OpenAiResponsesStream(Consumer<String> observer, ProviderErrors errors) {
        this.observer = observer == null ? ignored -> {} : observer;
        this.errors = errors;
    }

    boolean onEvent(String data) {
        if (completed != null) throw failure("完成后仍含事件");
        JsonNode event = parse(data);
        if (!event.isObject()) throw failure("事件结构无效");
        String type = text(event, "type");
        if (event.hasNonNull("sequence_number")) {
            JsonNode number = event.get("sequence_number");
            if (!number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < 0 || number.longValue() <= sequence)
                throw failure("事件序号无效或重复");
            sequence = number.longValue();
        }
        if (type.equals("error")) throw failure("厂商返回流式错误" + errors.detail(event));
        if (type.equals("response.failed") || type.equals("response.incomplete")) {
            JsonNode response = event.path("response");
            String reason = response.path("incomplete_details").path("reason").asText();
            String detail = errors.detail(response);
            if (Set.of("max_output_tokens", "content_filter").contains(reason)) detail += "；未完成原因：" + reason;
            throw failure("厂商未完成回复" + detail);
        }
        if (event.hasNonNull("error")) throw failure("厂商返回流式错误" + errors.detail(event));
        if (PROGRESS.contains(type)) {
            JsonNode response = event.path("response");
            if (!response.isObject()) throw failure("进度响应结构无效");
            observeResponseId(response);
            return true;
        }
        if (type.equals("response.output_text.delta")) {
            Part key = new Part(index(event, "output_index"), index(event, "content_index"));
            String itemId = text(event, "item_id");
            String delta = text(event, "delta");
            if (itemId.isBlank() || itemId.length() > 4096 || parts.size() >= MAX_PARTS && !parts.containsKey(key))
                throw failure("文本条目身份或数量无效");
            TextPart part = parts.computeIfAbsent(key, ignored -> new TextPart(itemId));
            if (!part.itemId.equals(itemId)) throw failure("文本条目身份发生变化");
            if (delta.length() > MAX_TEXT_CHARS - emitted.length()) throw failure("累计文本超过大小限制");
            part.text.append(delta);
            emitted.append(delta);
            if (!delta.isEmpty()) {
                try { observer.accept(delta); }
                catch (RuntimeException ignored) { /* 进度观察者不会改变协议完成状态。 */ }
            }
            return true;
        }
        if (type.equals("response.completed")) {
            JsonNode response = event.path("response");
            if (!response.isObject() || !"completed".equals(response.path("status").asText())
                    || response.hasNonNull("error") || response.hasNonNull("incomplete_details"))
                throw failure("完成事件缺少有效的完整响应" + errors.detail(response));
            observeResponseId(response);
            if (!response.path("output").isArray()) throw failure("完成响应缺少输出条目");
            verifyText(response);
            completed = response.deepCopy();
            return false;
        }
        if (METADATA.contains(type)) return true; // 完整输出项由 completed 统一验证，增量函数参数永不授权工具。
        throw failure("事件类型不受支持");
    }

    ChatResponse response(Function<JsonNode, ChatResponse> decoder) {
        if (completed == null) throw failure("在确认完成之前结束");
        ChatResponse response = decoder.apply(completed);
        if (!response.text().startsWith(emitted.toString())) throw failure("最终文本与已显示增量不一致");
        return response;
    }

    private void verifyText(JsonNode response) {
        for (var entry : parts.entrySet()) {
            JsonNode item = response.path("output").path(entry.getKey().output);
            JsonNode content = item.path("content").path(entry.getKey().content);
            if (!"message".equals(item.path("type").asText()) || !entry.getValue().itemId.equals(item.path("id").asText())
                    || !"output_text".equals(content.path("type").asText()) || !content.path("text").isTextual()
                    || !content.path("text").textValue().startsWith(entry.getValue().text.toString()))
                throw failure("最终文本条目与增量不一致");
        }
    }

    private void observeResponseId(JsonNode response) {
        if (!response.hasNonNull("id")) return;
        String value = text(response, "id");
        if (value.isBlank() || value.length() > 4096 || responseId != null && !responseId.equals(value))
            throw failure("响应身份发生变化");
        responseId = value;
    }

    static JsonNode parse(String value) {
        try {
            JsonNode node = STRICT.readTree(value);
            if (node == null) throw failure("包含空 JSON");
            return node;
        } catch (java.io.IOException ignored) { throw failure("包含格式错误的 JSON"); }
    }

    private static int index(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > MAX_PARTS)
            throw failure("文本条目索引无效");
        return value.intValue();
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw failure("事件文本字段缺失或格式错误");
        return node.path(field).textValue();
    }

    private static ProviderException failure(String message) { return new ProviderException("Responses 流：" + message); }
    private record Part(int output, int content) {}
    private static final class TextPart {
        final String itemId;
        final StringBuilder text = new StringBuilder();
        TextPart(String itemId) { this.itemId = itemId; }
    }
}
