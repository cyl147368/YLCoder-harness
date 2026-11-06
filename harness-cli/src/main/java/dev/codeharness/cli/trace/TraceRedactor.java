package dev.codeharness.cli.trace;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 供追踪、历史摘要和工具存档共用的凭据脱敏器；先脱敏，再截断。 */
public final class TraceRedactor {
    static final String MASK = "[已脱敏]";
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> CREDENTIAL_LABELS = List.of(
            "api_key", "api-key", "apikey", "access_token", "access-token", "accesstoken",
            "refresh_token", "refresh-token", "refreshtoken", "client_secret", "client-secret", "clientsecret",
            "private_key", "private-key", "privatekey", "authorization", "cookie", "password", "passwd", "secret", "token");
    private static final Pattern AUTH = Pattern.compile("(?i)(\\b(?:Bearer|Basic)\\s+)[A-Za-z0-9+/_.:=~\\-]+(?:\\[已脱敏\\])?");
    private static final Pattern COMMON = Pattern.compile("(?<![A-Za-z0-9])(?:sk-(?:proj-|svcacct-|ant-)?[A-Za-z0-9_\\-]{12,}|AIza[0-9A-Za-z_\\-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9\\-]{10,})(?![A-Za-z0-9])");
    private static final Pattern URL_AUTH = Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern HEADER_LINE = Pattern.compile("(?im)^(\\s*(?:authorization|proxy-authorization|x-api-key|api-key|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]*");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*?-----END (?:[A-Z ]+)?PRIVATE KEY-----");
    private final Set<String> secrets = new LinkedHashSet<>();
    private List<String> replacements = List.of();

    public TraceRedactor(Collection<String> values) { add(values); }

    void add(Collection<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            secrets.add(value);
            try {
                String escaped = JSON.writeValueAsString(value);
                escaped = escaped.substring(1, escaped.length() - 1);
                secrets.add(escaped);
                secrets.add(escaped.replace("/", "\\/"));
                String twice = JSON.writeValueAsString(escaped);
                secrets.add(twice.substring(1, twice.length() - 1));
            } catch (Exception ignored) { /* 原始凭据仍会被替换。 */ }
            StringBuilder unicode = new StringBuilder();
            for (int i = 0; i < value.length(); i++) unicode.append(String.format(Locale.ROOT, "\\u%04x", (int) value.charAt(i)));
            secrets.add(unicode.toString());
        }
        var ordered = new ArrayList<>(secrets);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        replacements = List.copyOf(ordered);
    }

    public String text(String value) {
        String result = value == null ? "" : value;
        for (String secret : replacements) result = result.replace(secret, MASK);
        result = HEADER_LINE.matcher(result).replaceAll("$1" + MASK);
        result = PRIVATE_KEY.matcher(result).replaceAll(MASK);
        result = AUTH.matcher(result).replaceAll("$1" + MASK);
        result = URL_AUTH.matcher(result).replaceAll("$1" + MASK + "@");
        result = COMMON.matcher(result).replaceAll(MASK);
        return redactCredentialValues(result);
    }

    /** 逐字符扫描凭据值，避免超长带引号内容触发正则重复分组的递归回溯。 */
    private static String redactCredentialValues(String value) {
        StringBuilder output = null;
        int copied = 0;
        int position = 0;
        while (position < value.length()) {
            if (!identifierCharacter(value.charAt(position))) { position++; continue; }
            int start = position;
            while (position < value.length() && identifierCharacter(value.charAt(position))) position++;
            int end = position;
            if (!credentialLabel(value, start, end)) continue;
            boolean flag = value.startsWith("--", start);
            int delimiter = end;
            if (start > 0 && delimiter < value.length()
                    && (value.charAt(start - 1) == '"' || value.charAt(start - 1) == '\'')
                    && value.charAt(delimiter) == value.charAt(start - 1)) delimiter++;
            while (delimiter < value.length() && whitespace(value.charAt(delimiter))) delimiter++;
            boolean assignment = delimiter < value.length() && (value.charAt(delimiter) == ':' || value.charAt(delimiter) == '=');
            int content;
            if (assignment) {
                content = delimiter + 1;
                while (content < value.length() && whitespace(value.charAt(content))) content++;
            } else if (flag && end < value.length() && whitespace(value.charAt(end))) {
                content = delimiter;
            } else continue;
            int contentEnd = credentialValueEnd(value, content, !assignment);
            if (contentEnd == content) continue;
            if (output == null) output = new StringBuilder(value.length());
            output.append(value, copied, content).append('"').append(MASK).append('"');
            copied = contentEnd;
            position = contentEnd;
        }
        return output == null ? value : output.append(value, copied, value.length()).toString();
    }

    private static int credentialValueEnd(String value, int start, boolean flag) {
        if (start >= value.length()) return start;
        char quote = value.charAt(start);
        if (quote == '"' || quote == '\'') {
            int position = start + 1;
            while (position < value.length()) {
                char current = value.charAt(position++);
                if (current == '\\' && position < value.length()) { position++; continue; }
                if (current == quote) {
                    // 兼容 PowerShell 的双写引号；没有闭合引号时省略剩余值。
                    if (position < value.length() && value.charAt(position) == quote) { position++; continue; }
                    return position;
                }
            }
            return value.length();
        }
        int position = start;
        while (position < value.length()) {
            char current = value.charAt(position);
            if (whitespace(current) || (!flag && ",;&{}[]()".indexOf(current) >= 0)) break;
            position++;
        }
        return position;
    }

    private static boolean credentialLabel(String value, int start, int end) {
        for (String label : CREDENTIAL_LABELS) {
            int suffix = end - label.length();
            if (suffix >= start && value.regionMatches(true, suffix, label, 0, label.length())) return true;
        }
        return false;
    }

    private static boolean identifierCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                || value == '_' || value == '-' || value == '.';
    }

    private static boolean whitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == '\u000b';
    }

    String arguments(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null) return text(value);
            scrub(node, 0);
            return text(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (Exception ignored) {
            return "[工具参数已省略：无法安全解析或脱敏]";
        }
    }

    private void scrub(JsonNode value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("日志 JSON 嵌套过深");
        if (value instanceof ObjectNode object) {
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode child = object.get(field);
                if (credentialKey(field)) object.put(field, MASK);
                else if (child.isTextual()) object.put(field, text(child.textValue()));
                else scrub(child, depth + 1);
            }
        } else if (value instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) array.set(i, JSON.getNodeFactory().textNode(text(child.textValue())));
                else scrub(child, depth + 1);
            }
        }
    }

    private static boolean credentialKey(String field) {
        String key = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.equals("token") || key.equals("password") || key.equals("passwd") || key.equals("secret")
                || key.endsWith("apikey") || key.endsWith("accesstoken") || key.endsWith("refreshtoken")
                || key.endsWith("authorization") || key.endsWith("clientsecret") || key.endsWith("privatekey")
                || key.endsWith("password") || key.endsWith("secretkey") || key.equals("cookie") || key.equals("setcookie");
    }
}
