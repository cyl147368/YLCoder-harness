package dev.codeharness.providers;

import java.net.URI;
import java.util.Map;

/** 使用 Azure OpenAI v1 兼容地址的 Azure OpenAI 适配器。 */
public final class AzureOpenAiProvider implements ModelProvider {
    @Override public String id() { return "azure-openai"; }

    @Override public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId())) throw new IllegalArgumentException("不支持的厂商 ID：" + config.providerId());
        return new OpenAiCompatibleProvider.Model(config) {
            @Override protected URI defaultBaseUri() {
                throw new IllegalArgumentException("baseUri 必须设置为 Azure OpenAI v1 地址");
            }
            @Override protected Map<String, String> protocolHeaders() {
                if (config.apiKey() == null || config.apiKey().isBlank()) return Map.of();
                return Map.of("api-key", config.apiKey());
            }
        };
    }
}
