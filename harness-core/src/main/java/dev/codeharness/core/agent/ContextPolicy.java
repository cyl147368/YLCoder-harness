package dev.codeharness.core.agent;

/** 触发阈值和目标之间保留余量；近期原文以用户轮次划分。 */
public record ContextPolicy(boolean semantic, double triggerRatio, double targetRatio, int recentTurns, int summaryMaxTokens) {
    public ContextPolicy {
        if (!Double.isFinite(triggerRatio) || !Double.isFinite(targetRatio)
                || targetRatio <= 0 || targetRatio >= triggerRatio || triggerRatio > 1)
            throw new IllegalArgumentException("压缩比例必须满足 0 < target < trigger <= 1");
        if (recentTurns < 1 || recentTurns > 100) throw new IllegalArgumentException("recent-turns 必须在 1～100 之间");
        if (summaryMaxTokens < 64 || summaryMaxTokens > 16384) throw new IllegalArgumentException("summary-max-tokens 必须在 64～16384 之间");
    }
    public static ContextPolicy disabled() { return new ContextPolicy(false, .85, .65, 4, 2048); }
}
