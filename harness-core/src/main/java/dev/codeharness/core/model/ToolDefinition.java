package dev.codeharness.core.model;

import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.Collections;
import java.util.Objects;

/** 暴露给模型的 JSON Schema 说明。 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        description = Objects.requireNonNullElse(description, "");
        inputSchema = canonicalMap(Objects.requireNonNullElse(inputSchema, Map.of()));
    }

    private static Map<String, Object> canonicalMap(Map<?, ?> map) {
        Map<String, Object> copy = new TreeMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException("Schema 键必须是字符串");
            copy.put(text, canonicalValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) return canonicalMap(map);
        if (value instanceof List<?> list) return list.stream().map(ToolDefinition::canonicalValue).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("不支持的 JSON Schema 值：" + value.getClass().getSimpleName());
    }
}
