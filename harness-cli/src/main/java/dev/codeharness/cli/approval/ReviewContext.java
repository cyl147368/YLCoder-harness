package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 有界的分类输入投影；不向审核模型转发助手说明、工具结果或厂商元数据。 */
final class ReviewContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 6_000;
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "run_command", Set.of("command"),
            "read_file", Set.of("path", "start_line", "end_line"),
            "list_files", Set.of("path"),
            "search_files", Set.of("path", "query"),
            "write_file", Set.of("path"),
            "edit_file", Set.of("path"));

    private ReviewContext() {}

    static List<Map<String, Object>> project(List<ChatMessage> messages) {
        var result = new ArrayList<IndexedItem>();
        int newestUser = -1;
        // 工具循环不能把任务本身挤出分类上下文；先给最近一条真实用户消息预留空间。
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() == ChatMessage.Role.USER) { newestUser = index; break; }
        }
        int remaining = LIMIT - 2; // 序列化列表的 [] 也计入总限制。
        if (newestUser >= 0) {
            Map<String, Object> user = latestUser(messages.get(newestUser).content());
            result.add(new IndexedItem(newestUser, user));
            remaining -= size(user);
        }
        for (int index = messages.size() - 1; index >= 0 && result.size() < 12 && remaining > 0; index--) {
            if (index == newestUser) continue;
            Map<String, Object> item = projectItem(messages.get(index));
            if (item == null) continue;
            int cost = size(item) + (result.isEmpty() ? 0 : 1);
            // 单个大工具投影不应使仍能放入的较早用户任务和小投影一并丢失。
            if (cost > remaining) continue;
            remaining -= cost;
            result.add(new IndexedItem(index, item));
        }
        result.sort(Comparator.comparingInt(IndexedItem::index));
        return result.stream().map(IndexedItem::item).toList();
    }

    private record IndexedItem(int index, Map<String, Object> item) {}

    private static Map<String, Object> latestUser(String content) {
        Map<String, Object> item = Map.of("role", "user", "content", clip(content, 1_200));
        if (size(item) <= LIMIT - 2) return item;
        // 控制字符经过 JSON 转义可膨胀为六字符；仍必须保留用户项且遵守实际序列化上限。
        String projected = (String) item.get("content");
        int lower = 0, upper = projected.length();
        while (lower < upper) {
            int middle = (lower + upper + 1) / 2;
            Map<String, Object> candidate = Map.of("role", "user", "content", projected.substring(0, middle) + "[已截断]");
            if (size(candidate) <= LIMIT - 2) lower = middle;
            else upper = middle - 1;
        }
        return Map.of("role", "user", "content", projected.substring(0, lower) + "[已截断]");
    }

    private static Map<String, Object> projectItem(ChatMessage message) {
        if (message.role() == ChatMessage.Role.USER)
            return Map.of("role", "user", "content", clip(message.content(), 1_200));
        if (message.role() != ChatMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) return null;
        var calls = new ArrayList<Map<String, Object>>();
        for (var call : message.toolCalls().subList(0, Math.min(message.toolCalls().size(), 4))) {
            var input = new LinkedHashMap<String, Object>();
            // 写入内容、替换文本、远端响应及未知工具参数均不参与分类。
            if (FIELDS.containsKey(call.name()) && call.argumentsJson().length() <= 8_192) {
                try {
                    var args = JSON.readTree(call.argumentsJson());
                    if (args != null && args.isObject()) args.fields().forEachRemaining(field -> {
                        if (FIELDS.get(call.name()).contains(field.getKey()) && field.getValue().isValueNode())
                            input.put(field.getKey(), clip(field.getValue().asText(), 240));
                    });
                } catch (java.io.IOException ignored) { /* 无效参数只保留工具名称。 */ }
            }
            calls.add(Map.of("tool", clip(call.name(), 80), "input", input));
        }
        return Map.of("role", "assistant_tools", "calls", calls);
    }

    private static int size(Map<String, Object> item) {
        try { return JSON.writeValueAsString(item).length(); }
        catch (java.io.IOException impossible) { return LIMIT + 1; }
    }

    private static String clip(String value, int limit) {
        // 只投影摘要；常见凭据格式不进入审核请求，配置对象本身从不传入。
        String redacted = value
                .replaceAll("(?i)(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{16,}|(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,})", "[已隐藏凭据]")
                .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[已隐藏凭据]@")
                .replaceAll("(?i)((?:[A-Za-z0-9_-]*(?:api[-_]?key|token|password|secret)|authorization)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s&,;\"']+)", "$1[已隐藏凭据]");
        return redacted.substring(0, Math.min(redacted.length(), limit))
                + (redacted.length() > limit ? "[已截断]" : "");
    }
}
