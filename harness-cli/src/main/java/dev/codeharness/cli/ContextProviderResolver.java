package dev.codeharness.cli;

import dev.codeharness.providers.ProviderConfig;
import java.util.function.Function;

/** 摘要连接复用已有厂商解析与凭据隔离规则，仅同一厂商同一地址可以默认继承密钥。 */
final class ContextProviderResolver {
    private ContextProviderResolver() {}
    static ProviderConfig resolve(HarnessConfig config, ProviderConfig main, Function<String, String> environment) {
        HarnessConfig summary = new HarnessConfig();
        summary.permissions.reviewer = config.agent.contextSummarizer;
        summary.permissions.reviewTimeoutSeconds = Math.min(30, config.timeoutSeconds);
        return ReviewProviderResolver.resolve(summary, main, environment);
    }
    static int sourceBudget(HarnessConfig config, String summaryModel) {
        HarnessConfig.Agent agent = new HarnessConfig.Agent();
        agent.contextBudgetTokens = Math.min(16_000, config.agent.contextBudgetTokens);
        agent.contextWindowTokens = config.agent.contextWindowTokens;
        agent.modelContextWindows = config.agent.modelContextWindows;
        agent.maxOutputTokens = config.agent.contextSummaryMaxTokens;
        agent.reasoningReserveTokens = config.agent.reasoningReserveTokens;
        agent.contextSafetyMarginTokens = config.agent.contextSafetyMarginTokens;
        return ModelContextBudget.resolve(agent, summaryModel).inputBudgetTokens();
    }
}
