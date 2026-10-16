package dev.codeharness.core.model;

import java.util.List;
import java.util.Objects;

/** 不可变模型请求。 */
public record ChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools, Integer maxOutputTokens) {
    public ChatRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
        if (messages.isEmpty()) throw new IllegalArgumentException("messages 不能为空");
        if (maxOutputTokens != null && maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens 必须为正数");
    }
}
