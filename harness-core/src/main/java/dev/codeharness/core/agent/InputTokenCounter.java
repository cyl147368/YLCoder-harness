package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolDefinition;
import java.util.List;

/** 本地计数策略；实现必须包含消息、调用元数据及工具结构，不能只统计正文。 */
public interface InputTokenCounter {
    int messageTokens(List<ChatMessage> messages);
    int toolTokens(List<ToolDefinition> tools);

    /** 兼容性估算；不是厂商分词器或计费测量。 */
    InputTokenCounter UTF16 = new InputTokenCounter() {
        @Override public int messageTokens(List<ChatMessage> messages) {
            long chars = 0;
            for (ChatMessage message : messages) {
                chars += (long) message.content().length() + 12;
                chars += length(message.toolCallId()) + length(message.toolName());
                for (var entry : message.providerMetadata().entrySet()) {
                    chars += (long) entry.getKey().length() + entry.getValue().length() + 8;
                }
                for (var call : message.toolCalls()) {
                    chars += (long) call.id().length() + call.name().length() + call.argumentsJson().length() + 16;
                    for (var entry : call.providerMetadata().entrySet()) {
                        chars += (long) entry.getKey().length() + entry.getValue().length() + 8;
                    }
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
        }
        @Override public int toolTokens(List<ToolDefinition> tools) { return ToolSchemaBudget.estimateTokens(tools); }
        private long length(String value) { return value == null ? 0 : value.length(); }
    };
}
