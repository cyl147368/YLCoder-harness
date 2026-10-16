package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 恢复前验证已有证据；缺失结果与互相矛盾的结果分别处理。 */
public final class ToolHistory {
    private ToolHistory() {}

    public static void validateCalls(List<ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (ToolCall call : calls) {
            if (!ids.add(call.id())) throw new IllegalArgumentException("响应包含重复的工具调用 ID");
        }
    }

    public static void validate(List<ChatMessage> history, boolean allowMissingResults) {
        Map<String, ToolCall> pending = new HashMap<>();
        for (ChatMessage message : history) {
            if (message.role() == ChatMessage.Role.TOOL) {
                ToolCall call = pending.remove(message.toolCallId());
                if (call == null) throw new IllegalArgumentException("历史记录包含孤立、重复或未匹配的工具结果");
                if (!call.name().equals(message.toolName())) throw new IllegalArgumentException("历史记录中工具结果名称不匹配");
            } else {
                if (!allowMissingResults && !pending.isEmpty()) throw new IllegalArgumentException("历史记录缺少工具结果");
                pending.clear();
                validateCalls(message.toolCalls());
                for (ToolCall call : message.toolCalls()) pending.put(call.id(), call);
            }
        }
        if (!allowMissingResults && !pending.isEmpty()) throw new IllegalArgumentException("历史记录缺少工具结果");
    }
}
