package dev.codeharness.cli;

import java.util.Locale;
import java.util.Map;

/** 输入预算与总窗口分开，显式模型窗口优先；内置目录只使用已知模型家族的保守容量。 */
final class ModelContextBudget {
    record Resolution(int inputBudgetTokens, int contextWindowTokens, int outputReserveTokens,
                      int reasoningReserveTokens, int safetyMarginTokens, String windowSource) {}

    private ModelContextBudget() {}

    static Resolution resolve(HarnessConfig.Agent agent, String model) {
        validate(agent);
        Integer mapped = model == null ? null : agent.modelContextWindows.get(model);
        Integer explicit = mapped != null ? mapped : agent.contextWindowTokens;
        Integer known = knownWindow(model);
        int output = agent.maxOutputTokens == null ? 3000 : agent.maxOutputTokens;
        long reserve = (long) output + agent.reasoningReserveTokens + agent.contextSafetyMarginTokens;
        long window = explicit != null ? explicit : known != null ? known :
                Math.min(Integer.MAX_VALUE, (long) agent.contextBudgetTokens + reserve);
        long available = window - reserve;
        if (available < 64) throw new IllegalArgumentException("上下文窗口不足：输出、推理和安全预留后必须至少剩余 64 个输入 token");
        int budget = (int) Math.min(agent.contextBudgetTokens, available);
        String source = mapped != null ? "模型配置" : explicit != null ? "通用配置" : known != null ? "内置保守目录" : "自定义输入预算";
        return new Resolution(budget, (int) window, output, agent.reasoningReserveTokens,
                agent.contextSafetyMarginTokens, source);
    }

    static void validate(HarnessConfig.Agent agent) {
        if (agent.contextBudgetTokens < 64 || agent.contextWindowTokens != null && agent.contextWindowTokens < 64
                || agent.contextSafetyMarginTokens < 0 || agent.reasoningReserveTokens < 0
                || agent.maxOutputTokens != null && agent.maxOutputTokens < 1)
            throw new IllegalArgumentException("上下文限制无效：输入/窗口至少 64，安全和推理预留不能为负，输出 token 须为正数");
        if (agent.modelContextWindows == null || agent.modelContextWindows.size() > 128)
            throw new IllegalArgumentException("agent.model-context-windows 必须是最多 128 项的模型窗口映射");
        for (Map.Entry<String, Integer> entry : agent.modelContextWindows.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank() || id.length() > 256 || id.codePoints().anyMatch(code -> Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)
                    || entry.getValue() == null || entry.getValue() < 64)
                throw new IllegalArgumentException("agent.model-context-windows 的模型名或窗口无效");
        }
    }

    static String canonicalModel(String model) {
        if (model == null) return "";
        String id = model.strip().toLowerCase(Locale.ROOT);
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    private static Integer knownWindow(String model) {
        String id = canonicalModel(model);
        if (id.startsWith("gpt-4.1")) return 1_000_000;
        if (id.startsWith("gpt-4o") || id.startsWith("gpt-4-turbo") || id.startsWith("gpt-4-0125")
                || id.startsWith("gpt-4-1106")) return 128_000;
        if (id.equals("gpt-4") || id.startsWith("gpt-4-0613")) return 8192;
        if (id.startsWith("gpt-3.5-turbo")) return 16_000;
        if (id.matches("o[134](?:-.*)?")) return 128_000;
        if (id.startsWith("claude-")) return 200_000;
        if (id.startsWith("gemini-1.5") || id.startsWith("gemini-2.5")) return 1_000_000;
        // 新版本、部署别名和厂商网关容量不推断；用户通过显式映射确认。
        return null;
    }
}
