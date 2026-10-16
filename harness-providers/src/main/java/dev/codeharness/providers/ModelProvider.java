package dev.codeharness.providers;

import dev.codeharness.core.model.ChatModel;

/** 厂商服务扩展接口：添加模型厂商无需修改代理循环。 */
public interface ModelProvider {
    /** 稳定厂商 ID，例如 {@code openai-compatible}。 */
    String id();

    ChatModel create(ProviderConfig config);
}
