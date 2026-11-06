package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.agent.*;
import dev.codeharness.core.model.*;
import java.util.*;

/** 确定性离线对照：固定模拟摘要，比较原历史、旧裁剪和新投影；计入摘要请求本地估算成本。 */
public final class ContextOptimizationBenchmark {
    private ContextOptimizationBenchmark() {}
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String language : List.of("zh", "en")) for (String encoding : List.of("utf16", "cl100k", "o200k", "conservative"))
            rows.add(run(language, encoding));
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "fixture", "context-optimization-v1", "measurement", "本地估算；固定模拟摘要；不代表厂商计费或模型语义能力", "rows", rows)));
    }
    private static Map<String, Object> run(String language, String encoding) {
        InputTokenCounter tokens = TokenizerInputTokenCounter.create(encoding);
        ContextCompactor counter = new ContextCompactor(tokens);
        boolean zh = language.equals("zh"); String requirement = zh ? "禁止删除数据库并保留审计" : "Never delete the database; preserve audit records";
        List<ChatMessage> original = new ArrayList<>(List.of(ChatMessage.system(zh ? "编程规则" : "Coding rules"), ChatMessage.user(zh ? "实现登录" : "Implement login")));
        for (int i = 0; i < 12; i++) {
            original.add(ChatMessage.user(i == 4 ? requirement : (zh ? "检查阶段" : "Inspect stage ") + i));
            original.add(ChatMessage.assistant((zh ? "原始文件检查详情与测试输出。" : "Original file inspection details and test output. ").repeat(250)));
        }
        original.add(ChatMessage.user(zh ? "继续测试" : "Continue testing"));
        int budget = Math.max(2000, counter.estimateTokens(original) / 3), rounds = 5;
        ContextSummary summary = new ContextSummary(List.of(zh ? "实现登录" : "Implement login"), List.of(requirement), List.of(), List.of("已检查"), List.of("测试"), List.of(), List.of("src/Auth.java"));
        long[] summaryCost = {0, 0}; int[] calls = {0};
        ChatModel model = request -> {
            summaryCost[0] += counter.estimateTokens(request.messages()); calls[0]++;
            try {
                String text = new ObjectMapper().writeValueAsString(summary);
                summaryCost[1] += counter.estimateTokens(List.of(ChatMessage.assistant(text)));
                return ChatResponse.text(text);
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        var semantic = new ModelContextSummarizer(model, tokens, 16000, List.of());
        var manager = new ContextManager(counter, new ContextPolicy(true, .85, .65, 1, 1500), semantic);
        long fullTotal = 0, oldTotal = 0, newTotal = 0, time = System.nanoTime();
        int baselineFirst = 0;
        ContextManager.Projection first = null; boolean baselineRetained = false, optimizedRetained = false;
        for (int i = 0; i < rounds; i++) {
            var baseline = counter.compact(original, budget, 8000);
            var modern = manager.project(original, budget, 8000);
            if (first == null) { first = modern; baselineFirst = counter.estimateTokens(baseline); }
            fullTotal += counter.estimateTokens(original); oldTotal += counter.estimateTokens(baseline); newTotal += counter.estimateTokens(modern.messages());
            baselineRetained = baseline.stream().anyMatch(m -> m.content().contains(requirement));
            optimizedRetained = modern.messages().stream().anyMatch(m -> m.content().contains(requirement));
            original.add(ChatMessage.assistant(zh ? "继续" : "Continue")); original.add(ChatMessage.user(zh ? "下一步" : "Next step"));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("budget_tokens", budget); row.put("baseline_first_tokens", baselineFirst);
        row.put("language", language); row.put("encoding", encoding); row.put("rounds", rounds);
        row.put("original_first_tokens", first.metrics().beforeTokens()); row.put("optimized_first_tokens", first.metrics().afterTokens());
        row.put("raw_input_total", fullTotal); row.put("baseline_input_total", oldTotal); row.put("optimized_input_total", newTotal);
        row.put("summary_input_tokens", summaryCost[0]); row.put("summary_output_tokens", summaryCost[1]); row.put("summary_requests", calls[0]);
        row.put("optimized_total_including_summary", newTotal + summaryCost[0] + summaryCost[1]);
        row.put("baseline_requirement_retained", baselineRetained); row.put("optimized_requirement_retained", optimizedRetained);
        row.put("degraded", first.metrics().degraded()); row.put("elapsed_ms", (System.nanoTime() - time) / 1_000_000);
        return row;
    }
}
