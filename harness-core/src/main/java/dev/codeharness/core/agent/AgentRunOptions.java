package dev.codeharness.core.agent;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 每个任务的限制及取消回调。 */
public record AgentRunOptions(String systemPrompt, int maxIterations, int contextTokenBudget,
                              Integer maxOutputTokens, int maxToolOutputChars, BooleanSupplier cancelled, int maxParallelReads) {
    public AgentRunOptions {
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "你是一名严谨的软件工程助手。使用可用工具检查并修改代码，然后验证结果。");
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations 必须为正数");
        if (contextTokenBudget < 64) throw new IllegalArgumentException("contextTokenBudget 至少为 64 token");
        if (maxOutputTokens != null && maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens 必须为正数");
        if (maxToolOutputChars < 128) throw new IllegalArgumentException("maxToolOutputChars 至少为 128 个字符");
        cancelled = Objects.requireNonNullElse(cancelled, () -> false);
        if (maxParallelReads < 1 || maxParallelReads > 8) throw new IllegalArgumentException("maxParallelReads 必须在 1 到 8 之间");
    }
    public AgentRunOptions(String systemPrompt, int maxIterations, int contextTokenBudget,
                           Integer maxOutputTokens, int maxToolOutputChars, BooleanSupplier cancelled) {
        this(systemPrompt, maxIterations, contextTokenBudget, maxOutputTokens, maxToolOutputChars, cancelled, 1);
    }
    public static AgentRunOptions defaults() {
        return new AgentRunOptions(null, 12, 12_000, 2_048, 8_000, () -> false);
    }
}
