package dev.codeharness.providers;

import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 localhost I/O 失败必须可诊断；测试不连接任何远程厂商。 */
class NativeTransportFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai-responses"})
    void 未收到完整HTTP正文时报告连接原因并允许降级(String provider) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/v1/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] comment = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, comment.length + 100);
            exchange.getResponseBody().write(comment);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        server.start();
        try {
            var model = model(provider, server.getAddress().getPort());
            var failure = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
            assertTrue(failure.streamFallbackSuggested(), failure.getMessage());
            assertTrue(failure.getMessage().contains("连接提前关闭") || failure.getMessage().contains("网络读写失败"), failure.getMessage());
            assertFalse(failure.getMessage().equals("流式请求失败"));
            assertNull(failure.getCause());
            assertEquals(1, requests.get(), "适配器不自行重试；重试预算由 CLI 统一管理");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai-compatible", "openai-responses"})
    void 连接被拒绝时同步和流式都保留分类且不泄露地址(String provider) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var model = model(provider, port);
        var stream = assertThrows(ProviderException.class, () -> model.complete(request(), ignored -> {}));
        assertTrue(stream.streamFallbackSuggested());
        assertTrue(stream.getMessage().contains("ConnectException"), stream.getMessage());
        assertTrue(stream.getMessage().contains("无法建立连接"));
        var sync = assertThrows(ProviderException.class, () -> model.complete(request()));
        assertFalse(sync.streamFallbackSuggested());
        assertTrue(sync.getMessage().contains("ConnectException"), sync.getMessage());
        for (var failure : List.of(stream, sync)) {
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("127.0.0.1"));
            assertFalse(failure.toString().contains("private-key"));
            assertFalse(failure.toString().contains("private-header"));
        }
    }

    private static ChatModel model(String provider, int port) {
        return new ProviderRegistry(List.of()).create(new ProviderConfig(provider, "fixture",
                URI.create("http://127.0.0.1:" + port + "/v1"), "private-key", Duration.ofSeconds(3),
                Map.of("X-Api-Token", "private-header")));
    }

    private static ChatRequest request() {
        return new ChatRequest(List.of(ChatMessage.user("检查项目")), List.of(), 100);
    }
}
