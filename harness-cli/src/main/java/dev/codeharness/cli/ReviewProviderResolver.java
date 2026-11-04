package dev.codeharness.cli;

import dev.codeharness.providers.ProviderConfig;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 解析独立审核连接；切换厂商或端点时不得隐式转发主模型凭据。 */
public final class ReviewProviderResolver {
    private ReviewProviderResolver() {}

    public static ProviderConfig resolve(HarnessConfig config, ProviderConfig main,
                                         Function<String, String> environment) {
        validate(config);
        Objects.requireNonNull(main, "主模型连接不能为空");
        Objects.requireNonNull(environment, "环境变量读取器不能为空");
        HarnessConfig.Reviewer reviewer = config.permissions.reviewer;
        String provider = reviewer.provider == null ? main.providerId()
                : reviewer.provider.trim().toLowerCase(Locale.ROOT);
        boolean sameProvider = provider.equals(main.providerId());
        URI baseUri = reviewer.baseUrl == null ? (sameProvider ? main.baseUri() : null)
                : reviewBaseUri(reviewer.baseUrl);
        boolean sameConnection = sameProvider && Objects.equals(baseUri, main.baseUri());
        String key;
        if (reviewer.apiKey != null && !reviewer.apiKey.isBlank()) key = reviewer.apiKey;
        else if (reviewer.apiKeyEnv != null) key = readEnvironment(reviewer.apiKeyEnv, environment);
        else if (sameConnection) key = main.apiKey();
        else key = readEnvironment(defaultKeyEnvironment(provider), environment);
        if (key != null && containsLineBreak(key)) {
            throw new IllegalArgumentException("审核模型密钥不能含换行或空字符");
        }

        Map<String, String> headers;
        if (reviewer.headerEnv == null) headers = sameConnection ? main.headers() : Map.of();
        else {
            headers = new LinkedHashMap<>();
            for (var header : reviewer.headerEnv.entrySet()) {
                String value = readEnvironment(header.getValue(), environment);
                if (value == null || value.isBlank() || containsLineBreak(value)) {
                    throw new IllegalArgumentException("审核请求头环境变量未设置、为空或含换行；请检查 permissions.reviewer.header-env");
                }
                headers.put(header.getKey(), value);
            }
        }
        String model = reviewer.model == null ? main.model() : reviewer.model.trim();
        // 仅切换同一连接的请求格式可复用凭据；显式更换厂商或地址仍沿用上面的隔离规则。
        return new ProviderConfig(ApiFormat.provider(provider, reviewer.apiFormat), model, baseUri, key,
                Duration.ofSeconds(config.permissions.reviewTimeoutSeconds), headers);
    }

    static void validate(HarnessConfig config) {
        if (config == null || config.permissions == null) {
            throw new IllegalArgumentException("permissions 不能为空");
        }
        config.permissions.effectiveMode();
        if (config.permissions.reviewTimeoutSeconds < 1 || config.permissions.reviewTimeoutSeconds > 30) {
            throw new IllegalArgumentException("permissions.review-timeout-seconds 必须为 1..30");
        }
        HarnessConfig.Reviewer reviewer = config.permissions.reviewer;
        if (reviewer == null) throw new IllegalArgumentException("permissions.reviewer 不能为 null");
        ApiFormat.validate(reviewer.apiFormat);
        if ((reviewer.provider != null && (reviewer.provider.isBlank() || containsLineBreak(reviewer.provider)))
                || (reviewer.model != null && (reviewer.model.isBlank() || containsLineBreak(reviewer.model)))) {
            throw new IllegalArgumentException("permissions.reviewer.provider 和 model 如有配置则不能为空白或含换行");
        }
        if (reviewer.baseUrl != null) reviewBaseUri(reviewer.baseUrl);
        if (reviewer.apiKeyEnv != null && !reviewer.apiKeyEnv.isEmpty() && !validEnvironmentName(reviewer.apiKeyEnv)) {
            throw new IllegalArgumentException("permissions.reviewer.api-key-env 必须是环境变量名称，或留空以禁用密钥");
        }
        if (reviewer.apiKey != null && containsLineBreak(reviewer.apiKey)) {
            throw new IllegalArgumentException("permissions.reviewer.api-key 不能含换行");
        }
        if (reviewer.headerEnv != null) {
            for (var header : reviewer.headerEnv.entrySet()) {
                if (header.getKey() == null || !header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || !validEnvironmentName(header.getValue())) {
                    throw new IllegalArgumentException("permissions.reviewer.header-env 必须将有效 HTTP 请求头名称映射到环境变量名称");
                }
            }
        }
    }

    private static URI reviewBaseUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException invalid) {
            // URI 解析异常会包含原始值，必须去掉原因链，避免暴露误填的凭据。
            throw new IllegalArgumentException("permissions.reviewer.base-url 必须是有效的 HTTP(S) 地址，且不能含用户信息、查询或片段");
        }
    }

    private static String readEnvironment(String name, Function<String, String> environment) {
        if (name == null || name.isEmpty()) return null;
        try { return environment.apply(name); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException("无法读取审核模型的环境变量；请检查 permissions.reviewer 配置");
        }
    }

    private static boolean validEnvironmentName(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static String defaultKeyEnvironment(String provider) {
        return switch (provider) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "azure-openai", "azure" -> "AZURE_OPENAI_API_KEY";
            case "ollama" -> null;
            default -> "OPENAI_API_KEY";
        };
    }
}
