package dev.codeharness.core.model;

import java.util.function.Consumer;

/** 一次模型轮次的厂商无关接口。 */
@FunctionalInterface
public interface ChatModel {
    ChatResponse complete(ChatRequest request);

    /** 厂商支持流式输出时提供真实可见文本片段，绝不从最终响应伪造。 */
    default ChatResponse complete(ChatRequest request, Consumer<String> onTextDelta) {
        return complete(request);
    }
}
