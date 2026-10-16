package dev.codeharness.cli.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.codeharness.core.agent.ContextCompactor;
import dev.codeharness.cli.TokenizerInputTokenCounter;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 离线测量；序列化 token 数不能用于宣称厂商计费或编程质量。 */
public final class ContextBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int BUDGET = 1_200;
    private static final int TOOL_LIMIT = 1_000;
    private static final int SAMPLES = 25;
    private static final String SOURCE = "public class Range { static boolean contains(int x, int low, int high) { return x >= low && x <= high; } }\n";
    private static final List<Map<String, Object>> SCHEMAS = List.of(Map.of(
            "name", "read_file", "description", "读取大小受限的源代码范围",
            "parameters", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")),
                    "required", List.of("path"), "additionalProperties", false)));

    private ContextBenchmark() {}

    public static void main(String[] args) throws Exception {
        var registry = Encodings.newDefaultEncodingRegistry();
        Encoding cl100k = registry.getEncoding(EncodingType.CL100K_BASE);
        Encoding o200k = registry.getEncoding(EncodingType.O200K_BASE);
        if (args.length == 1 && args[0].equals("--count-stdin")) {
            byte[] bytes = System.in.readNBytes(8_000_001);
            if (bytes.length > 8_000_000) throw new IllegalArgumentException("tokenizer 批次超过 8 MB");
            var input = JSON.readTree(bytes);
            if (input == null || !input.isArray() || input.size() > 1_000) throw new IllegalArgumentException("最多接受 1000 个 JSON 字符串");
            List<Map<String, Integer>> counts = new ArrayList<>();
            for (var value : input) {
                if (!value.isTextual()) throw new IllegalArgumentException("tokenizer 输入必须包含字符串");
                counts.add(Map.of("cl100k", cl100k.countTokensOrdinary(value.textValue()),
                        "o200k", o200k.countTokensOrdinary(value.textValue()),
                        "utf8Bytes", value.textValue().getBytes(StandardCharsets.UTF_8).length));
            }
            System.out.println(JSON.writeValueAsString(counts));
            return;
        }
        String counterName = "utf16";
        if (args.length == 2 && args[0].equals("--token-counter")) counterName = args[1];
        else if (args.length != 0) throw new IllegalArgumentException("用法：ContextBenchmark [--count-stdin | --token-counter utf16|cl100k|o200k]");
        if (!TokenizerInputTokenCounter.supported(counterName)) throw new IllegalArgumentException("不支持的 token 计数器");
        List<Map<String, Object>> results = new ArrayList<>();
        for (String kind : List.of("code-history", "diagnostic-history", "multilingual-history")) {
            results.add(measure(kind, cl100k, o200k, counterName));
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "benchmarkVersion", 2, "tokenCounter", counterName, "environment", Map.of("java", System.getProperty("java.version"),
                        "os", System.getProperty("os.name"), "tokenizer", "jtokkit 1.1.0"),
                "scope", "离线合成上下文；包含固定 schema 的序列化。没有模型调用，也不作计费主张。",
                "samples", SAMPLES, "messageEstimateBudget", BUDGET, "toolOutputChars", TOOL_LIMIT, "cases", results)));
    }

    static List<ChatMessage> fixture(String kind) {
        if (!List.of("code-history", "diagnostic-history", "multilingual-history").contains(kind)) throw new IllegalArgumentException("未知测试夹具");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("仅编辑 Range.java。保留方法签名。验证包含两端边界的行为。"));
        messages.add(ChatMessage.user("修复 Range.contains，使其包含两个边界值。保留无关修改。"));
        for (int cycle = 0; cycle < 30; cycle++) {
            ToolCall call = new ToolCall("history-" + cycle, "read_file", "{\"path\":\"history/" + cycle + ".txt\"}");
            messages.add(ChatMessage.assistantToolCalls("检查历史诊断", List.of(call)));
            StringBuilder result = new StringBuilder();
            for (int line = 0; line < 80; line++) {
                result.append(switch (kind) {
                    case "code-history" -> "private int value_" + cycle + "_" + line + "(int argument) { return argument + " + (cycle * 83 + line) + "; }\n";
                    case "diagnostic-history" -> "module-" + cycle + "/Case" + line + ".java:" + (line + 1) + ": 类型不兼容：预期 int，实际 String；seed=" + (cycle * 83 + line) + "\n";
                    default -> "测试记录 " + cycle + ":" + line + " 保留边界条件、异常与原始用户需求。検証結果を確認してください。\n";
                });
            }
            messages.add(ChatMessage.toolResult(call, result.toString()));
        }
        messages.add(ChatMessage.user("验证当前源代码；下界和上界都必须包含在内。"));
        ToolCall current = new ToolCall("current-source", "read_file", "{\"path\":\"Range.java\",\"start_line\":1}",
                Map.of("fixture.signature", "opaque-signature-kept-exactly"));
        messages.add(ChatMessage.assistantToolCalls("检查当前实现", List.of(current)));
        messages.add(ChatMessage.toolResult(current, SOURCE));
        return List.copyOf(messages);
    }

    static void verifyRetention(List<ChatMessage> before, List<ChatMessage> after) {
        List<ChatMessage> protectedMessages = new ArrayList<>();
        protectedMessages.add(before.getFirst());
        protectedMessages.add(before.get(1));
        protectedMessages.add(before.get(before.size() - 3));
        protectedMessages.add(before.get(before.size() - 2));
        protectedMessages.add(before.getLast());
        if (!after.containsAll(protectedMessages)) throw new IllegalStateException("受保护的指令、任务、当前代码或调用元数据发生了变化");
        Map<String, ToolCall> pending = new LinkedHashMap<>();
        for (ChatMessage message : after) {
            for (ToolCall call : message.toolCalls()) {
                if (pending.put(call.id(), call) != null) throw new IllegalStateException("调用重复");
            }
            if (message.role() == ChatMessage.Role.TOOL && pending.remove(message.toolCallId()) == null) throw new IllegalStateException("结果没有对应调用");
        }
        if (!pending.isEmpty()) throw new IllegalStateException("缺少结果");
    }

    private static Map<String, Object> measure(String kind, Encoding cl100k, Encoding o200k, String counterName) throws Exception {
        var compactor = new ContextCompactor(TokenizerInputTokenCounter.create(counterName));
        List<ChatMessage> before = fixture(kind);
        for (int i = 0; i < 5; i++) compactor.compact(before, BUDGET, TOOL_LIMIT);
        long[] times = new long[SAMPLES];
        List<ChatMessage> after = List.of();
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            after = compactor.compact(before, BUDGET, TOOL_LIMIT);
            times[i] = System.nanoTime() - started;
        }
        verifyRetention(before, after);
        if (compactor.estimateTokens(after) > BUDGET) throw new IllegalStateException("所选本地计数预算已超出");
        String original = serialize(before);
        String compacted = serialize(after);
        Arrays.sort(times);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixture", kind);
        result.put("fixtureSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))));
        result.put("before", metrics(before, original, compactor, cl100k, o200k));
        result.put("after", metrics(after, compacted, compactor, cl100k, o200k));
        result.put("retentionChecks", "PASS：system、原始/最新 user、当前代码和调用元数据保持精确一致；工具周期成对");
        result.put("compactionMs", Map.of("p50", times[12] / 1_000_000.0, "p95", times[23] / 1_000_000.0));
        return result;
    }

    private static String serialize(List<ChatMessage> messages) throws Exception {
        return JSON.writeValueAsString(Map.of("messages", messages, "tools", SCHEMAS));
    }

    private static Map<String, Object> metrics(List<ChatMessage> messages, String serialized, ContextCompactor compactor,
                                             Encoding cl100k, Encoding o200k) {
        return Map.of("messages", messages.size(), "messageEstimate", compactor.estimateTokens(messages),
                "serializedUtf8Bytes", serialized.getBytes(StandardCharsets.UTF_8).length,
                "serializedCl100kTokens", cl100k.countTokensOrdinary(serialized),
                "serializedO200kTokens", o200k.countTokensOrdinary(serialized));
    }
}
