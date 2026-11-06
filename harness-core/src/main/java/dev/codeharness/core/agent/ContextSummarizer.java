package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.TokenUsage;
import java.util.List;

/** 独立无工具摘要请求；必须只总结传入的原始历史，不递归总结旧摘要。 */
@FunctionalInterface
public interface ContextSummarizer {
    Result summarize(List<ChatMessage> original, int maxOutputTokens);
    /** 摘要验证失败也要计入已经消耗的厂商用量；异常只保留固定说明。 */
    final class Failure extends RuntimeException {
        private final TokenUsage usage;
        public Failure(TokenUsage usage) { super("摘要未通过结构或预算验证"); this.usage = usage == null ? TokenUsage.ZERO : usage; }
        public TokenUsage usage() { return usage; }
    }
    record Result(ContextSummary summary, TokenUsage usage) {
        public Result { if (summary == null) throw new IllegalArgumentException("摘要为空"); usage = usage == null ? TokenUsage.ZERO : usage; }
    }
}
