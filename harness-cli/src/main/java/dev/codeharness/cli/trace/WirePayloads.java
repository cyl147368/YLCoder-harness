package dev.codeharness.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** 对脱敏后的报文做本地去重；引用单独使用 JSON Pointer 索引，不占用厂商字段。 */
final class WirePayloads {
    private static final int MAX_ENTRIES = 65_536;
    private final Map<String, String> entries = new HashMap<>();
    private long sequence;

    ObjectNode project(JsonNode value) throws Exception {
        var result = TraceRedactor.JSON.createObjectNode();
        var references = TraceRedactor.JSON.createObjectNode();
        var additions = TraceRedactor.JSON.createObjectNode();
        result.set("body", walk(value, "", references, additions));
        result.set("references", references);
        result.set("new_content", additions);
        return result;
    }

    private JsonNode walk(JsonNode value, String pointer, ObjectNode refs, ObjectNode additions) throws Exception {
        // 工具结构整项去重；其余结构保留原字段，只抽取长文本，避免历史增加一条就重存整个数组。
        boolean tool = pointer.matches("/tools/[0-9]+");
        if (tool || value.isTextual() && (value.textValue().length() >= 80 || pointer.matches(".*/(content|text|instructions|arguments|system|input)$"))) {
            String json = TraceRedactor.JSON.writeValueAsString(value);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            String id = entries.get(hash);
            if (id == null) {
                // 上限时保留正文，不产生无法定位的引用；文件总大小限制仍由调用方控制。
                if (entries.size() >= MAX_ENTRIES) return value;
                id = "wire-" + ++sequence;
                entries.put(hash, id);
                additions.set(id, value);
            }
            refs.put(pointer, id);
            return TraceRedactor.JSON.getNodeFactory().textNode("[引用 " + id + "]");
        }
        if (value.isObject()) {
            ObjectNode result = TraceRedactor.JSON.createObjectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String part = field.getKey().replace("~", "~0").replace("/", "~1");
                result.set(field.getKey(), walk(field.getValue(), pointer + "/" + part, refs, additions));
            }
            return result;
        }
        if (value.isArray()) {
            var result = TraceRedactor.JSON.createArrayNode();
            for (int index = 0; index < value.size(); index++) result.add(walk(value.get(index), pointer + "/" + index, refs, additions));
            return result;
        }
        return value;
    }
    void clear() { entries.clear(); }
}
