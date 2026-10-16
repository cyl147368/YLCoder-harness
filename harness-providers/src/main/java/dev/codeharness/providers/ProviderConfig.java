package dev.codeharness.providers;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/** 创建模型适配器所需的非机密厂商设置；API 密钥不写入持久化配置。 */
public record ProviderConfig(
        String providerId,
        String model,
        URI baseUri,
        String apiKey,
        Duration timeout,
        Map<String, String> headers) {

    public ProviderConfig {
        providerId = requireText(providerId, "providerId").toLowerCase(Locale.ROOT);
        model = requireText(model, "model");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout 必须为正数");
        headers = Map.copyOf(Objects.requireNonNullElse(headers, Map.of()));
        if (baseUri != null && !("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalArgumentException("baseUri 必须使用 HTTP 或 HTTPS");
        }
        if (baseUri != null && (baseUri.getHost() == null || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null)) {
            throw new IllegalArgumentException("baseUri 必须包含主机名，且不能含用户信息、查询或片段；请通过请求头认证");
        }
    }

    public ProviderConfig(String providerId, String model, URI baseUri, String apiKey) {
        this(providerId, model, baseUri, apiKey, Duration.ofMinutes(2), Map.of());
    }

    public static ProviderConfig of(String providerId, String model, String apiKey) {
        return new ProviderConfig(providerId, model, null, apiKey);
    }

    @Override public String toString() {
        return "ProviderConfig[providerId=" + providerId + ", model=" + model + ", baseUri=" + (baseUri == null ? "default" : "[configured]")
                + ", apiKey=[redacted], timeout=" + timeout + ", headers=[redacted]]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
