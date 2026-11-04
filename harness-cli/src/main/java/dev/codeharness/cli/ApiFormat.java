package dev.codeharness.cli;

import java.util.Locale;
import java.util.Set;

/** 将 OpenAI 协议选择与厂商地址分离；省略时保持既有 provider 行为。 */
final class ApiFormat {
    private static final Set<String> OPENAI = Set.of("openai", "openai-compatible", "openai-responses");
    private ApiFormat() {}

    static String validate(String format) {
        if (format == null) return null;
        String value = format.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("chat", "responses").contains(value))
            throw new IllegalArgumentException("api-format 必须是 chat 或 responses；留空字段请直接删除");
        return value;
    }

    static String provider(String provider, String format) {
        String value = validate(format);
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider 不能为空");
        String selected = provider.strip().toLowerCase(Locale.ROOT);
        if (value == null) return selected;
        if (!OPENAI.contains(selected))
            throw new IllegalArgumentException("api-format 仅适用于 openai、openai-compatible 或 openai-responses");
        if (value.equals("responses")) return "openai-responses";
        return selected.equals("openai-responses") ? "openai" : selected;
    }
}
