package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅记录用量字段是否真实存在，避免把协议缺省值零显示成实测值。 */
final class UsageReporting {
    private UsageReporting() {}

    static boolean reported(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    static boolean reported(Integer value) { return value != null && value >= 0; }

    static boolean optionalCountValid(JsonNode node, String field) {
        return !node.has(field) || reported(node, field);
    }

    static Map<String, String> metadata(boolean input, boolean output, boolean cache) {
        return with(Map.of(), input, output, cache);
    }

    static Map<String, String> with(Map<String, String> existing, boolean input, boolean output, boolean cache) {
        var metadata = new LinkedHashMap<>(existing);
        metadata.put("usage.inputReported", Boolean.toString(input));
        metadata.put("usage.outputReported", Boolean.toString(output));
        metadata.put("usage.cacheReported", Boolean.toString(cache));
        return Map.copyOf(metadata);
    }
}
