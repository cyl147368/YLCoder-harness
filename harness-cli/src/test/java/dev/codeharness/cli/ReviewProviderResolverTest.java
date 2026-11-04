package dev.codeharness.cli;

import dev.codeharness.providers.ProviderConfig;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReviewProviderResolverTest {
    private final HarnessConfig config = new HarnessConfig();
    private final ProviderConfig main = new ProviderConfig("openai-compatible", "main-model",
            URI.create("https://main.example/v1"), "main-fixture-secret", Duration.ofSeconds(120),
            Map.of("X-Private-Header", "main-header-secret"));

    @Test void 默认继承主连接并使用独立超时() {
        config.permissions.reviewTimeoutSeconds = 4;
        ProviderConfig actual = resolve(Map.of());
        assertEquals(main.providerId(), actual.providerId());
        assertEquals(main.model(), actual.model());
        assertEquals(main.baseUri(), actual.baseUri());
        assertEquals(main.apiKey(), actual.apiKey());
        assertEquals(main.headers(), actual.headers());
        assertEquals(Duration.ofSeconds(4), actual.timeout());
    }

    @Test void 只覆盖模型不改变连接凭据() {
        config.permissions.reviewer.model = "small-review-model";
        ProviderConfig actual = resolve(Map.of("OPENAI_API_KEY", "unused-env-secret"));
        assertEquals("small-review-model", actual.model());
        assertEquals(main.baseUri(), actual.baseUri());
        assertEquals(main.apiKey(), actual.apiKey());
        assertEquals(main.headers(), actual.headers());
    }

    @Test void 仅覆盖格式保留自定义连接及凭据() {
        config.permissions.reviewer.apiFormat = "responses";
        ProviderConfig actual = resolve(Map.of("OPENAI_API_KEY", "unused-env-secret"));
        assertEquals("openai-responses", actual.providerId());
        assertEquals(main.baseUri(), actual.baseUri());
        assertEquals(main.apiKey(), actual.apiKey());
        assertEquals(main.headers(), actual.headers());
    }

    @Test void 已选择Responses的主模型默认让审核继承格式且可改用Chat() {
        ProviderConfig responses = new ProviderConfig("openai-responses", main.model(), main.baseUri(),
                main.apiKey(), main.timeout(), main.headers());
        assertEquals("openai-responses", ReviewProviderResolver.resolve(config, responses, name -> null).providerId());
        config.permissions.reviewer.apiFormat = "chat";
        ProviderConfig actual = ReviewProviderResolver.resolve(config, responses, name -> null);
        assertEquals("openai", actual.providerId());
        assertEquals(main.baseUri(), actual.baseUri());
        assertEquals(main.apiKey(), actual.apiKey());
        assertEquals(main.headers(), actual.headers());
    }

    @Test void 格式覆盖不能绕过新端点的凭据隔离() {
        config.permissions.reviewer.apiFormat = "responses";
        config.permissions.reviewer.baseUrl = "https://review.example/v1";
        ProviderConfig actual = resolve(Map.of());
        assertEquals("openai-responses", actual.providerId());
        assertEquals(URI.create(config.permissions.reviewer.baseUrl), actual.baseUri());
        assertNull(actual.apiKey());
        assertTrue(actual.headers().isEmpty());
    }

    @Test void 非OpenAI协议不能被格式字段隐式替换() {
        config.permissions.reviewer.provider = "anthropic";
        config.permissions.reviewer.apiFormat = "responses";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> resolve(Map.of()));
        assertTrue(failure.getMessage().contains("api-format"));
        config.permissions.reviewer.apiFormat = "unknown-fixture-secret";
        assertSanitized(assertThrows(IllegalArgumentException.class, () -> resolve(Map.of())), "unknown-fixture-secret");
    }

    @Test void 显式选择同一厂商和端点仍可继承() {
        config.permissions.reviewer.provider = "OPENAI-COMPATIBLE";
        config.permissions.reviewer.baseUrl = main.baseUri().toString();
        ProviderConfig actual = resolve(Map.of());
        assertEquals(main.apiKey(), actual.apiKey());
        assertEquals(main.headers(), actual.headers());
    }

    @Test void 更换端点不转发主模型密钥和请求头() {
        config.permissions.reviewer.baseUrl = "https://review.example/v1";
        ProviderConfig actual = resolve(Map.of());
        assertEquals(URI.create(config.permissions.reviewer.baseUrl), actual.baseUri());
        assertNull(actual.apiKey());
        assertTrue(actual.headers().isEmpty());
    }

    @Test void 更换厂商使用其默认端点和默认密钥变量() {
        config.permissions.reviewer.provider = "anthropic";
        config.permissions.reviewer.model = "review-model";
        ProviderConfig actual = resolve(Map.of("ANTHROPIC_API_KEY", "anthropic-fixture-secret"));
        assertEquals("anthropic", actual.providerId());
        assertNull(actual.baseUri());
        assertEquals("anthropic-fixture-secret", actual.apiKey());
        assertTrue(actual.headers().isEmpty());
    }

    @Test void 更换厂商即使指定相同端点也不继承凭据() {
        config.permissions.reviewer.provider = "openai-responses";
        config.permissions.reviewer.baseUrl = main.baseUri().toString();
        ProviderConfig actual = resolve(Map.of());
        assertNull(actual.apiKey());
        assertTrue(actual.headers().isEmpty());
    }

    @Test void 更换端点可显式使用目标厂商默认变量() {
        config.permissions.reviewer.baseUrl = "https://review.example/v1";
        assertEquals("review-env-fixture", resolve(Map.of("OPENAI_API_KEY", "review-env-fixture")).apiKey());
    }

    @Test void 私有配置密钥优先于审核环境变量() {
        config.permissions.reviewer.apiKey = "inline-review-fixture";
        config.permissions.reviewer.apiKeyEnv = "REVIEW_TOKEN";
        assertEquals("inline-review-fixture", resolve(Map.of("REVIEW_TOKEN", "env-review-fixture")).apiKey());
    }

    @Test void 显式审核环境变量不会回退到主模型密钥() {
        config.permissions.reviewer.apiKeyEnv = "REVIEW_TOKEN";
        assertNull(resolve(Map.of()).apiKey());
        assertEquals("env-review-fixture", resolve(Map.of("REVIEW_TOKEN", "env-review-fixture")).apiKey());
        config.permissions.reviewer.apiKeyEnv = "";
        assertNull(resolve(Map.of("OPENAI_API_KEY", "unused-secret")).apiKey());
    }

    @Test void 显式请求头映射替换而不合并主模型请求头() {
        config.permissions.reviewer.headerEnv = Map.of("X-Review", "REVIEW_HEADER");
        assertEquals(Map.of("X-Review", "review-header-fixture"),
                resolve(Map.of("REVIEW_HEADER", "review-header-fixture")).headers());
        config.permissions.reviewer.headerEnv = Map.of();
        assertTrue(resolve(Map.of()).headers().isEmpty());
    }

    @Test void 更换端点可以解析自己的密钥和请求头() {
        config.permissions.reviewer.baseUrl = "https://review.example/v1";
        config.permissions.reviewer.apiKeyEnv = "REVIEW_TOKEN";
        config.permissions.reviewer.headerEnv = Map.of("X-Review", "REVIEW_HEADER");
        ProviderConfig actual = resolve(Map.of("REVIEW_TOKEN", "review-token-fixture",
                "REVIEW_HEADER", "review-header-fixture"));
        assertEquals("review-token-fixture", actual.apiKey());
        assertEquals(Map.of("X-Review", "review-header-fixture"), actual.headers());
        assertFalse(actual.toString().contains("review-token-fixture"));
        assertFalse(actual.toString().contains("review-header-fixture"));
    }

    @Test void 本地模型不读取默认云端密钥() {
        config.permissions.reviewer.provider = "ollama";
        ProviderConfig actual = ReviewProviderResolver.resolve(config, main, name -> fail("不应读取云端环境变量"));
        assertNull(actual.apiKey());
        assertNull(actual.baseUri());
        assertTrue(actual.headers().isEmpty());
    }

    @Test void 端点错误不会泄露地址中误填的秘密() {
        for (String value : new String[]{"https://secret-fixture@review.example", "https://review.example?key=secret-fixture",
                "https://review.example/#secret-fixture", "secret-fixture invalid", "file:///secret-fixture"}) {
            config.permissions.reviewer.baseUrl = value;
            assertSanitized(assertThrows(IllegalArgumentException.class, () -> resolve(Map.of())), "secret-fixture");
        }
    }

    @Test void 环境变量和请求头失败只返回脱敏诊断() {
        config.permissions.reviewer.headerEnv = Map.of("X-Review", "SECRET_FIXTURE");
        assertSanitized(assertThrows(IllegalArgumentException.class, () -> resolve(Map.of())), "SECRET_FIXTURE");
        assertSanitized(assertThrows(IllegalArgumentException.class,
                () -> resolve(Map.of("SECRET_FIXTURE", "secret-fixture\r\ninvalid"))), "secret-fixture");
        config.permissions.reviewer.headerEnv = null;
        config.permissions.reviewer.apiKeyEnv = "SECRET_FIXTURE";
        assertSanitized(assertThrows(IllegalArgumentException.class, () ->
                ReviewProviderResolver.resolve(config, main, name -> { throw new IllegalArgumentException("secret-fixture"); })),
                "secret-fixture");
        assertSanitized(assertThrows(IllegalArgumentException.class,
                () -> resolve(Map.of("SECRET_FIXTURE", "secret-fixture\ninvalid"))), "secret-fixture");
    }

    @Test void 旧布尔配置保持原有优先级且显式模式优先() {
        assertEquals("review", config.permissions.effectiveMode());
        config.permissions.autoReview = false;
        assertEquals("manual", config.permissions.effectiveMode());
        config.permissions.allowCommands = true;
        assertEquals("allow-all", config.permissions.effectiveMode());
        config.permissions.mode = "manual";
        assertEquals("manual", config.permissions.effectiveMode());
        config.permissions.mode = "REVIEW";
        assertEquals("review", config.permissions.effectiveMode());
        config.permissions.mode = "unsupported-secret";
        assertSanitized(assertThrows(IllegalArgumentException.class, config.permissions::effectiveMode), "unsupported-secret");
    }

    private ProviderConfig resolve(Map<String, String> environment) {
        return ReviewProviderResolver.resolve(config, main, environment::get);
    }

    private static void assertSanitized(Throwable error, String secret) {
        assertFalse(error.toString().contains(secret));
        assertNull(error.getCause());
    }
}
