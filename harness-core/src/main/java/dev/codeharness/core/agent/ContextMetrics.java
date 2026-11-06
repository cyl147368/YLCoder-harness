package dev.codeharness.core.agent;

/** 本地估算和厂商摘要用量分别记录；不得将估算值冒充账单。 */
public record ContextMetrics(int beforeTokens, int afterTokens, int budgetTokens, int summarizedMessages,
                             boolean reused, boolean degraded, long elapsedMillis, String reason) {}
