package dev.codeharness.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 归一化模型响应。 */
public record ChatResponse(String text, List<ToolCall> toolCalls, TokenUsage usage, FinishReason finishReason,
                           Map<String, String> providerMetadata) {
    public ChatResponse {
        text = Objects.requireNonNullElse(text, "");
        toolCalls = List.copyOf(Objects.requireNonNullElse(toolCalls, List.of()));
        usage = Objects.requireNonNullElse(usage, TokenUsage.ZERO);
        finishReason = Objects.requireNonNullElse(finishReason, toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS);
        providerMetadata = Map.copyOf(Objects.requireNonNullElse(providerMetadata, Map.of()));
    }
    public ChatResponse(String text, List<ToolCall> toolCalls, TokenUsage usage, FinishReason finishReason) {
        this(text, toolCalls, usage, finishReason, Map.of());
    }
    public static ChatResponse text(String text) { return new ChatResponse(text, List.of(), TokenUsage.ZERO, FinishReason.STOP); }
    public static ChatResponse text(String text, Map<String, String> metadata) { return new ChatResponse(text, List.of(), TokenUsage.ZERO, FinishReason.STOP, metadata); }
}
