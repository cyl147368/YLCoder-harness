package dev.codeharness.providers;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.MalformedInputException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class TransportErrorsTest {
    private static final String PRIVATE = "https://private.invalid/v1?api_key=sk-fixture-secret Bearer private-header \u001b[31m private-prompt";

    @Test void 包装连接错误保留具体原因但不传播秘密() {
        var failure = TransportErrors.stream(new IOException(PRIVATE, new ConnectException(PRIVATE)));
        assertTrue(failure.streamFallbackSuggested());
        assertTrue(failure.getMessage().contains("ConnectException"));
        assertTrue(failure.getMessage().contains("无法建立连接"));
        assertSafe(failure);
    }

    @Test void 网络故障给出可操作原因并允许流式降级() {
        var errors = List.of(
                new UnknownHostException(PRIVATE), new SocketException(PRIVATE), new EOFException(PRIVATE),
                new HttpTimeoutException(PRIVATE), new HttpConnectTimeoutException(PRIVATE), new IOException(PRIVATE));
        for (Throwable original : errors) {
            var stream = TransportErrors.stream(new IOException(PRIVATE, original));
            assertTrue(stream.streamFallbackSuggested(), original.getClass().getSimpleName());
            assertTrue(stream.getMessage().contains(original.getClass().getSimpleName()));
            assertTrue(stream.getMessage().contains("请检查"));
            assertSafe(stream);
            var sync = TransportErrors.request("openai-responses", new IOException(PRIVATE, original));
            assertFalse(sync.streamFallbackSuggested(), "同步请求不得引入嵌套降级重试");
            assertTrue(sync.getMessage().startsWith("openai-responses 请求失败"));
            assertSafe(sync);
        }
    }

    @Test void 证书编码配置错误不通过自动重试掩盖() {
        for (Throwable original : List.of(new SSLHandshakeException(PRIVATE), new MalformedInputException(1),
                new IllegalArgumentException(PRIVATE), new SecurityException(PRIVATE))) {
            var failure = TransportErrors.stream(new IOException(PRIVATE, original));
            assertFalse(failure.streamFallbackSuggested(), original.getClass().getSimpleName());
            assertSafe(failure);
        }
    }

    @Test void 未知客户端异常显示类型但不自动重试() {
        var failure = TransportErrors.stream(new IllegalStateException(PRIVATE));
        assertFalse(failure.streamFallbackSuggested());
        assertTrue(failure.getMessage().contains("IllegalStateException"));
        assertSafe(failure);
    }

    @Test void 取消原因嵌套在网络错误内仍然不重试() {
        for (Throwable original : List.of(new InterruptedException(PRIVATE), new CancellationException(PRIVATE),
                new ClosedByInterruptException())) {
            var failure = TransportErrors.stream(new IOException(PRIVATE, original));
            assertFalse(failure.streamFallbackSuggested());
            assertTrue(failure.getMessage().contains("中断或取消"));
            assertSafe(failure);
        }
    }

    @Test void 总时限诊断包含配置值且可降级() {
        var failure = TransportErrors.streamTimeout(Duration.ofMillis(1250));
        assertTrue(failure.streamFallbackSuggested());
        assertTrue(failure.getMessage().contains("1250 毫秒"));
        assertTrue(failure.getMessage().contains("timeout-seconds"));
        assertSafe(failure);
    }

    @Test void 循环异常链不会卡住错误处理() {
        var first = new IOException(PRIVATE);
        var second = new ConnectException(PRIVATE);
        first.initCause(second);
        second.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            var failure = TransportErrors.stream(first);
            assertTrue(failure.streamFallbackSuggested());
            assertTrue(failure.getMessage().contains("ConnectException"));
            assertSafe(failure);
        });
    }

    private static void assertSafe(ProviderException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.statusCode());
        assertFalse(failure.toString().contains("private"));
        assertFalse(failure.toString().contains("sk-fixture"));
        assertFalse(failure.toString().contains("Bearer"));
        assertFalse(failure.toString().contains("\u001b"));
    }
}
