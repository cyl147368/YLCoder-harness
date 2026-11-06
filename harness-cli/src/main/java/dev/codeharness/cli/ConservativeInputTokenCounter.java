package dev.codeharness.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.codeharness.core.agent.InputTokenCounter;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolDefinition;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;

/** 未知分词器的多语言估算，包含 JSON/元数据和额外余量；不冒充厂商计费数。 */
final class ConservativeInputTokenCounter implements InputTokenCounter {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_CHARS = 1_048_576;
    private final IdentityHashMap<Object, Count> cache = new IdentityHashMap<>();
    private final ArrayDeque<Object> order = new ArrayDeque<>();
    private int cacheChars;

    @Override public synchronized int messageTokens(List<ChatMessage> messages) {
        long total = 16;
        for (var message : messages) total += count(message) + 8L;
        return saturated(total);
    }

    @Override public synchronized int toolTokens(List<ToolDefinition> tools) {
        if (tools.isEmpty()) return 0;
        long total = 64;
        for (var tool : tools) total += count(tool) + 8L;
        return saturated(total);
    }

    private int count(Object value) {
        Count found = cache.get(value);
        if (found != null) return found.tokens;
        String text;
        try { text = JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("无法序列化输入进行 token 计数"); }
        long ascii = 0;
        long nonAscii = 0;
        for (int index = 0; index < text.length();) {
            int code = text.codePointAt(index);
            index += Character.charCount(code);
            if (code < 128) ascii++;
            // BMP 多语言字符最多按三个单字节 token 预留；补充字符按四个预留。
            else nonAscii += code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
        }
        long estimated = (ascii + 2) / 3 + nonAscii;
        int tokens = saturated((estimated * 11 + 9) / 10);
        if (text.length() <= MAX_CHARS) {
            while (!order.isEmpty() && (cache.size() >= MAX_ENTRIES
                    || (long) cacheChars + text.length() > MAX_CHARS)) {
                cacheChars -= cache.remove(order.removeFirst()).chars;
            }
            cache.put(value, new Count(tokens, text.length()));
            order.addLast(value);
            cacheChars += text.length();
        }
        return tokens;
    }

    private static int saturated(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
    private record Count(int tokens, int chars) {}
}
