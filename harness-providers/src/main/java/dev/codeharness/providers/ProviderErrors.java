package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 只提取诊断字段；凭据脱敏先于长度截断，禁止透传整份响应或请求。 */
final class ProviderErrors {
    static final int MAX_BODY_BYTES = 16 * 1024;
    static final int MAX_DETAIL_CHARS = 1024;
    private final List<String> secrets;

    ProviderErrors(ProviderConfig config) {
        var values = new ArrayList<String>();
        if (config != null) {
            add(values, config.apiKey());
            config.headers().values().forEach(value -> add(values, value));
        }
        secrets = values.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private static void add(List<String> values, String value) {
        if (value == null || value.isBlank()) return;
        values.add(value);
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) values.add(value.substring(7));
    }

    String read(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) return "；厂商详情过长，已省略";
        return detail(new String(bytes, StandardCharsets.UTF_8));
    }

    String detail(String body) {
        if (body == null || body.isBlank()) return "";
        if (body.length() > MAX_BODY_BYTES) return "；厂商详情过长，已省略";
        String trimmed = body.strip();
        try {
            JsonNode root = AbstractHttpChatModel.JSON.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(trimmed);
            if (root == null) return "";
            return detail(root);
        } catch (IOException ignored) {
            // HTML 错误页或损坏的 JSON 可能含整份请求，只展示明确的纯文本错误。
            if (trimmed.startsWith("<") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return "；厂商返回了非结构化错误页，详情已省略";
            }
            return suffix(trimmed);
        }
    }

    String detail(JsonNode root) {
        JsonNode error = root.hasNonNull("error") ? root.get("error") : root;
        if (error.isTextual()) return suffix(error.textValue());
        var fields = new ArrayList<String>();
        for (String name : List.of("message", "detail", "type", "code", "param", "status")) {
            JsonNode value = error.path(name);
            if (value.isValueNode() && !value.isNull()) fields.add(name + "=" + value.asText());
        }
        return suffix(String.join("；", fields));
    }

    private String suffix(String value) {
        if (value.isBlank()) return "";
        String safe = redact(value);
        safe = safe.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").strip();
        safe = redact(safe);
        if (safe.length() > MAX_DETAIL_CHARS) safe = safe.substring(0, MAX_DETAIL_CHARS) + "…（已截断）";
        return safe.isEmpty() ? "" : "；厂商详情：" + safe;
    }

    private String redact(String value) {
        for (String secret : secrets) value = value.replace(secret, "[已脱敏]");
        return value.replaceAll("(?i)\\bBearer\\s+[^\\s,;\"'<>]+", "Bearer [已脱敏]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[已脱敏]")
                .replaceAll("(?i)((?:api[_-]?key|access[_-]?token|authorization|password)\\s*[=:]\\s*)[^\\s,;\"'<>]+", "$1[已脱敏]");
    }
}
