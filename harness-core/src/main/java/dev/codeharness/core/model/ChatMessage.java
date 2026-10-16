package dev.codeharness.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 厂商无关会话中的一条消息。 */
public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName,
                          Map<String, String> providerMetadata) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = Objects.requireNonNullElse(content, "");
        toolCalls = List.copyOf(Objects.requireNonNullElse(toolCalls, List.of()));
        providerMetadata = Map.copyOf(Objects.requireNonNullElse(providerMetadata, Map.of()));
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) throw new IllegalArgumentException("只有 assistant 消息可以包含工具调用");
        if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) throw new IllegalArgumentException("工具结果必须提供 toolCallId");
    }

    /** 保持源码兼容的构造器，适用于不含厂商不透明回放证据的会话。 */
    public ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        this(role, content, toolCalls, toolCallId, toolName, Map.of());
    }

    public static ChatMessage system(String content) { return new ChatMessage(Role.SYSTEM, content, List.of(), null, null); }
    public static ChatMessage user(String content) { return new ChatMessage(Role.USER, content, List.of(), null, null); }
    public static ChatMessage assistant(String content) { return new ChatMessage(Role.ASSISTANT, content, List.of(), null, null); }
    public static ChatMessage assistant(String content, Map<String, String> metadata) { return new ChatMessage(Role.ASSISTANT, content, List.of(), null, null, metadata); }
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls) { return new ChatMessage(Role.ASSISTANT, content, calls, null, null); }
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls, Map<String, String> metadata) { return new ChatMessage(Role.ASSISTANT, content, calls, null, null, metadata); }
    public static ChatMessage toolResult(ToolCall call, String content) { return new ChatMessage(Role.TOOL, content, List.of(), call.id(), call.name()); }
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
