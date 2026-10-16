package dev.codeharness.providers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/** 解析内置及类路径提供的模型适配器。 */
public final class ProviderRegistry {
    private final Map<String, ModelProvider> providers;

    public ProviderRegistry() {
        Map<String, ModelProvider> discovered = new LinkedHashMap<>();
        OpenAiCompatibleProvider openAiCompatible = new OpenAiCompatibleProvider();
        register(discovered, openAiCompatible);
        discovered.put("openai", openAiCompatible);
        discovered.put("ollama", openAiCompatible);
        register(discovered, new AnthropicProvider());
        register(discovered, new GeminiProvider());
        register(discovered, new AzureOpenAiProvider());
        register(discovered, new LangChain4jOpenAiProvider());
        register(discovered, new OpenAiResponsesProvider());
        ServiceLoader.load(ModelProvider.class).forEach(provider -> register(discovered, provider));
        providers = Map.copyOf(discovered);
    }

    public ProviderRegistry(Iterable<ModelProvider> extensions) {
        Map<String, ModelProvider> discovered = new LinkedHashMap<>();
        OpenAiCompatibleProvider openAiCompatible = new OpenAiCompatibleProvider();
        register(discovered, openAiCompatible);
        discovered.put("openai", openAiCompatible);
        discovered.put("ollama", openAiCompatible);
        register(discovered, new AnthropicProvider());
        register(discovered, new GeminiProvider());
        register(discovered, new AzureOpenAiProvider());
        register(discovered, new LangChain4jOpenAiProvider());
        register(discovered, new OpenAiResponsesProvider());
        extensions.forEach(provider -> register(discovered, provider));
        providers = Map.copyOf(discovered);
    }

    public ModelProvider provider(String id) {
        if (id == null) throw new IllegalArgumentException("必须提供厂商 ID");
        ModelProvider provider = providers.get(id.toLowerCase(Locale.ROOT));
        if (provider == null) throw new IllegalArgumentException("未知模型厂商：" + id);
        return provider;
    }

    public dev.codeharness.core.model.ChatModel create(ProviderConfig config) {
        return provider(config.providerId()).create(config);
    }

    public Map<String, ModelProvider> providers() { return providers; }

    private static void register(Map<String, ModelProvider> providers, ModelProvider provider) {
        String id = provider.id().toLowerCase(Locale.ROOT);
        if (id.isBlank()) throw new IllegalArgumentException("厂商 ID 不能为空");
        providers.put(id, provider);
    }
}
