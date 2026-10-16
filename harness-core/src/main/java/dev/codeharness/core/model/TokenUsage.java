package dev.codeharness.core.model;

/** 厂商报告的 token 数；厂商未提供的值为零。 */
public record TokenUsage(long inputTokens, long outputTokens, long cachedInputTokens) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) throw new IllegalArgumentException("token 数不能为负数");
    }
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens, cachedInputTokens + other.cachedInputTokens);
    }
}
