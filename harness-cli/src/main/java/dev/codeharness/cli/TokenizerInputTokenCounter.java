package dev.codeharness.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.codeharness.core.agent.InputTokenCounter;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolDefinition;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;

/** 对规范化的本地 JSON 计数，并显式预留消息封装开销。厂商线上格式和计费方式可能不同。 */
public final class TokenizerInputTokenCounter implements InputTokenCounter {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final int MAX_CACHE_CHARS = 1_048_576;
    private final Encoding encoding;
    // 身份键避免重复计算大型不可变内容的哈希；仅保留计数和引用。
    private final IdentityHashMap<ChatMessage, Count> cache = new IdentityHashMap<>();
    private final ArrayDeque<ChatMessage> order = new ArrayDeque<>();
    private int cacheChars;

    public TokenizerInputTokenCounter(EncodingType type) {
        if (type != EncodingType.CL100K_BASE && type != EncodingType.O200K_BASE) {
            throw new IllegalArgumentException("不支持的上下文 tokenizer");
        }
        encoding = Registry.INSTANCE.getEncoding(type);
    }

    public static boolean supported(String value) {
        return "utf16".equals(value) || "cl100k".equals(value) || "o200k".equals(value);
    }

    public static InputTokenCounter create(String value) {
        if (!supported(value)) throw new IllegalArgumentException("token-counter 必须是 utf16、cl100k 或 o200k");
        return switch (value) {
            case "cl100k" -> new TokenizerInputTokenCounter(EncodingType.CL100K_BASE);
            case "o200k" -> new TokenizerInputTokenCounter(EncodingType.O200K_BASE);
            default -> InputTokenCounter.UTF16;
        };
    }

    @Override public synchronized int messageTokens(List<ChatMessage> messages) {
        long total = 16; // 预留整体封装开销；每条消息还预留八个 token 的封装开销。
        for (ChatMessage message : messages) {
            Count count = cache.get(message);
            if (count == null) {
                String serialized = serialize(message);
                count = new Count(encoding.countTokensOrdinary(serialized) + 8, serialized.length());
                if (count.chars <= MAX_CACHE_CHARS) {
                    while (!order.isEmpty() && (cache.size() >= MAX_CACHE_ENTRIES
                            || (long) cacheChars + count.chars > MAX_CACHE_CHARS)) {
                        cacheChars -= cache.remove(order.removeFirst()).chars;
                    }
                    cache.put(message, count);
                    order.addLast(message);
                    cacheChars += count.chars;
                }
            }
            total += count.tokens;
        }
        return saturated(total);
    }

    @Override public int toolTokens(List<ToolDefinition> tools) {
        if (tools.isEmpty()) return 0;
        long total = 64; // 单独预留工具封装开销，包括 MCP 发现的 schema。
        for (ToolDefinition tool : tools) total += (long) encoding.countTokensOrdinary(serialize(tool)) + 8;
        return saturated(total);
    }

    private static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无法序列化输入进行 token 计数");
        }
    }

    private static int saturated(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
    private record Count(int tokens, int chars) {}
    private static final class Registry {
        private static final com.knuddels.jtokkit.api.EncodingRegistry INSTANCE = Encodings.newLazyEncodingRegistry();
    }
}
