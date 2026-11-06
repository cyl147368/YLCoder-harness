package dev.codeharness.providers;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 可选的 HTTP 正文观察器；不读取请求头，也不改变请求或响应内容。 */
public final class HttpExchangeTrace {
    public static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private HttpExchangeTrace() { }

    public interface Listener {
        void request(int attempt, String method, String endpoint, String body);
        void response(int attempt, int status, String contentType, String body, boolean truncated, String state);
    }

    public static Scope observe(Listener listener) {
        Scope scope = new Scope(listener, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Listener listener;
        private final Scope previous;
        private final List<Exchange> exchanges = new ArrayList<>();
        private Scope(Listener listener, Scope previous) { this.listener = listener; this.previous = previous; }
        public boolean observed() { return !exchanges.isEmpty(); }
        @Override public void close() {
            // 超时、取消或 SDK 未回调时也要保存已经收到的片段，迟到的回调不能写入下一任务。
            for (Exchange exchange : exchanges) exchange.finish("调用结束，响应读取未完成");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static Exchange start(String method, URI endpoint, String body) {
        Scope scope = CURRENT.get();
        if (scope == null) return new Exchange(null, 0);
        Exchange exchange = new Exchange(scope.listener, scope.exchanges.size() + 1);
        scope.exchanges.add(exchange);
        try {
            // URL 中的用户信息和查询参数可能含密钥，因此只记录地址和路径。
            String address = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(),
                    endpoint.getPath(), null, null).toString();
            scope.listener.request(exchange.attempt, method, address, body);
        } catch (Exception ignored) { /* 观察器失败不改变模型调用。 */ }
        return exchange;
    }

    static final class Exchange {
        private final Listener listener;
        private final int attempt;
        private final ByteArrayOutputStream bytes;
        private int status;
        private String contentType = "未报告";
        private boolean truncated;
        private boolean ended;
        Exchange(Listener listener, int attempt) {
            this.listener = listener;
            this.attempt = attempt;
            bytes = listener == null ? null : new ByteArrayOutputStream();
        }
        synchronized void headers(int status, String contentType) {
            if (ended || listener == null) return;
            this.status = status;
            this.contentType = contentType == null ? "未报告" : contentType;
        }
        synchronized void accept(byte[] value, int offset, int count) {
            if (ended || listener == null || count <= 0) return;
            int remaining = Math.max(0, MAX_CAPTURE_BYTES - bytes.size());
            bytes.write(value, offset, Math.min(remaining, count));
            if (count > remaining) truncated = true;
        }
        void accept(ByteBuffer value) {
            if (listener == null) return;
            ByteBuffer copy = value.asReadOnlyBuffer();
            byte[] chunk = new byte[Math.min(copy.remaining(), 8192)];
            while (copy.hasRemaining()) { int size = Math.min(copy.remaining(), chunk.length); copy.get(chunk, 0, size); accept(chunk, 0, size); }
        }
        void accept(String value) {
            if (listener == null || value == null) return;
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            accept(encoded, 0, encoded.length);
        }
        InputStream capture(InputStream input) {
            if (listener == null) return input;
            return new FilterInputStream(input) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) { byte[] one = {(byte) value}; accept(one, 0, 1); }
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = in.read(buffer, offset, length);
                    accept(buffer, offset, count);
                    return count;
                }
            };
        }
        synchronized void finish(String state) {
            if (ended) return;
            ended = true;
            if (listener == null) return;
            try { listener.response(attempt, status, contentType, bytes.toString(StandardCharsets.UTF_8), truncated, state); }
            catch (RuntimeException ignored) { /* 日志失败不能覆盖模型结果。 */ }
            finally { bytes.reset(); }
        }
    }
}
