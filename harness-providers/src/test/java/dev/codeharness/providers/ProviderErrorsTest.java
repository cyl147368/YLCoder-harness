package dev.codeharness.providers;

import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderErrorsTest {
    private ProviderConfig config(String id, URI uri) {
        return new ProviderConfig(id, "fixture", uri, "fixture-key-value", Duration.ofSeconds(3),
                Map.of("Authorization", "Bearer override-key-value", "x-private", "header-key-value"));
    }

    @Test void 诊断先脱敏再截断并忽略非诊断字段() throws Exception {
        var errors = new ProviderErrors(config("openai-compatible", null));
        var json = AbstractHttpChatModel.JSON.createObjectNode();
        json.put("request", "不能显示的请求内容");
        json.putObject("error").put("message", "参数无效\n\u001b[31m fixture-key-value override-key-value header-key-value sk-unknown-test-key")
                .put("code", "invalid_parameter").put("param", "max_tokens").put("request", "不能显示的请求内容");
        String detail = errors.detail(json.toString());
        assertTrue(detail.contains("参数无效"));
        assertTrue(detail.contains("code=invalid_parameter"));
        assertTrue(detail.contains("param=max_tokens"));
        for (String secret : List.of("fixture-key-value", "override-key-value", "header-key-value", "sk-unknown-test-key", "不能显示的请求内容")) {
            assertFalse(detail.contains(secret), detail);
        }
        assertFalse(detail.codePoints().anyMatch(Character::isISOControl));
        String longMessage = errors.detail("x".repeat(1018) + "fixture-key-value" + "x".repeat(200));
        assertTrue(longMessage.contains("已截断"));
        assertFalse(longMessage.contains("fixture"));
        assertTrue(longMessage.length() < 1100);
        assertTrue(errors.read(new ByteArrayInputStream(new byte[ProviderErrors.MAX_BODY_BYTES + 1])).contains("过长"));
        assertTrue(errors.detail("<html>fixture-key-value</html>").contains("已省略"));
        assertEquals("", errors.detail("{}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai", "azure-openai", "ollama", "anthropic", "gemini", "openai-responses"})
    void 原生厂商HTTP错误显示详情并过滤所有凭据(String provider) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"error\":{\"message\":\"不支持参数 fixture-key-value override-key-value header-key-value\",\"code\":\"invalid_parameter\",\"param\":\"max_tokens\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var model = new ProviderRegistry().create(config(provider, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")));
            var request = new ChatRequest(List.of(ChatMessage.user("测试")), List.of(), 32);
            var error = assertThrows(ProviderException.class, () -> model.complete(request));
            assertEquals(400, error.statusCode());
            assertTrue(error.getMessage().contains("不支持参数"), error.getMessage());
            assertTrue(error.getMessage().contains("param=max_tokens"));
            assertFalse(error.toString().contains("key-value"));
            assertNull(error.getCause());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "sse", "json-error"})
    void 流式错误显示脱敏详情(String mode) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                String body = "{\"error\":{\"message\":\"上游暂不可用 fixture-key-value override-key-value\",\"code\":\"upstream_error\"}}";
                if (mode.equals("sse")) body = "data: " + body + "\n\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", mode.equals("sse") ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(mode.equals("http") ? 400 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var model = new ProviderRegistry().create(config("openai-compatible", URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
            var error = assertThrows(ProviderException.class, () -> model.complete(new ChatRequest(List.of(ChatMessage.user("测试")), List.of(), 32), delta -> fail("错误不能成为回复")));
            assertTrue(error.getMessage().contains("上游暂不可用"));
            assertTrue(error.getMessage().contains("code=upstream_error"));
            assertFalse(error.toString().contains("key-value"));
        } finally { server.stop(0); }
    }
}
