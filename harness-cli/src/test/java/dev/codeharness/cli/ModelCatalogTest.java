package dev.codeharness.cli;

import com.sun.net.httpserver.HttpServer;
import dev.codeharness.providers.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 模型选择目录只访问 localhost，验证认证、合并顺序和失败降级。 */
class ModelCatalogTest {
    @Test void mergesCurrentConfiguredRecentAndDiscoveredModelsWithoutDuplicates() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"deepseek-v4-pro\"},{\"id\":\"deepseek-flash\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HarnessConfig config = new HarnessConfig();
            config.model = "current-model";
            config.models = List.of("deepseek-flash", "configured-model");
            ProviderConfig provider = new ProviderConfig("openai-compatible", config.model,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "fixture-key", Duration.ofSeconds(2), Map.of());

            ModelCatalog.Result result = ModelCatalog.discover(config, provider, List.of("recent-model", "current-model"));

            assertTrue(result.warning().isBlank());
            assertEquals(List.of("current-model", "deepseek-flash", "configured-model", "recent-model",
                    "deepseek-v4-pro"), result.options().stream().map(ModelCatalog.Option::id).toList());
            assertEquals("Bearer fixture-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void 收到响应头后正文停滞也必须在总时限内返回离线模型() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"data\":[".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            HarnessConfig config = new HarnessConfig();
            config.model = "offline-model";
            ProviderConfig provider = new ProviderConfig("openai-compatible", config.model,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "fixture-key", Duration.ofSeconds(2), Map.of());
            var pending = executor.submit(() -> ModelCatalog.discover(config, provider, List.of()));
            ModelCatalog.Result result = pending.get(7, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("offline-model", result.options().getFirst().id());
            assertFalse(result.warning().isBlank(), "正文停滞时应明确提示降级到离线候选");
            assertFalse(result.warning().contains("fixture-key"));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test void failedDiscoveryKeepsOfflineCandidatesAndReturnsSafeWarning() {
        HarnessConfig config = new HarnessConfig();
        config.model = "current-model";
        config.models = List.of("configured-model");
        ProviderConfig provider = new ProviderConfig("openai-compatible", config.model,
                URI.create("http://127.0.0.1:1/v1"), "fixture-key", Duration.ofSeconds(1), Map.of());

        ModelCatalog.Result result = ModelCatalog.discover(config, provider, List.of());

        assertEquals(List.of("current-model", "configured-model"),
                result.options().stream().map(ModelCatalog.Option::id).toList());
        assertTrue(result.warning().contains("无法读取厂商模型列表"));
        assertFalse(result.warning().contains("fixture-key"));
    }
}
