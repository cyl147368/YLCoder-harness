package dev.codeharness.core.agent;

import dev.codeharness.core.model.ToolDefinition;
import java.util.List;
import java.util.Map;

/** 本地输入估算；厂商分词器与传输封装开销各不相同。 */
public final class ToolSchemaBudget {
    private ToolSchemaBudget() {}

    public static int estimateTokens(List<ToolDefinition> tools) {
        if (tools.isEmpty()) return 0;
        long characters = 2;
        for (ToolDefinition tool : tools) {
            characters += 80 + jsonLength(tool.name()) + jsonLength(tool.description()) + jsonLength(tool.inputSchema());
        }
        return (int) Math.min(Integer.MAX_VALUE, (characters + 3) / 4);
    }

    private static long jsonLength(Object value) {
        if (value == null) return 4;
        if (value instanceof String text) {
            long size = 2;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                size += c < 32 ? 6 : c == '"' || c == '\\' ? 2 : 1;
            }
            return size;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 2;
            for (var entry : map.entrySet()) size += jsonLength(entry.getKey().toString()) + 2 + jsonLength(entry.getValue());
            return size;
        }
        if (value instanceof List<?> list) {
            long size = 2;
            for (Object item : list) size += 1 + jsonLength(item);
            return size;
        }
        return value.toString().length();
    }
}
