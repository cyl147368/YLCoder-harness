package dev.codeharness.providers;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** 有边界的 SSE/JSON 传输。总截止时间涵盖响应头和正文，不自动重试。 */
final class NativeStreamTransport {
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    static final int MAX_EVENT_CHARS = 1024 * 1024;
    private NativeStreamTransport() {}

    static String exchange(HttpClient client, HttpRequest request, Duration timeout, Predicate<String> onEvent) {
        var body = new AtomicReference<InputStream>();
        var abandoned = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("harness-native-stream").factory());
        var pending = executor.submit(() -> {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                InputStream stream = response.body();
                body.set(stream);
                if (abandoned.get()) throw new ProviderException("流式请求已取消");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ProviderException("流式请求失败，HTTP 状态码 " + response.statusCode(), response.statusCode());
                }
                String media = response.headers().firstValue("content-type").orElse("")
                        .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                var limited = new LimitedInput(stream);
                if (media.equals("text/event-stream")) {
                    parseEvents(limited, event -> {
                        if (abandoned.get() || Thread.currentThread().isInterrupted()) {
                            throw new ProviderException("流式请求已取消");
                        }
                        return onEvent.test(event);
                    });
                    return null;
                }
                if (!media.isEmpty() && !media.equals("application/json")) {
                    throw new ProviderException("流式响应的内容类型不受支持");
                }
                var bytes = new ByteArrayOutputStream();
                limited.transferTo(bytes);
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            } finally {
                closeAsync(body.getAndSet(null));
            }
        });
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderException("流式请求已中断");
        } catch (TimeoutException expired) {
            throw new ProviderException("流式请求超时");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof ProviderException provider) throw provider;
            throw new ProviderException("流式请求失败");
        } finally {
            abandoned.set(true);
            pending.cancel(true);
            closeAsync(body.getAndSet(null));
            executor.shutdownNow();
        }
    }

    private static void closeAsync(InputStream stream) {
        if (stream == null) return;
        // 不守约的管道关闭操作不能延长调用者的取消等待或总截止时间。
        Thread.ofVirtual().name("harness-native-stream-close").start(() -> {
            try { stream.close(); } catch (IOException ignored) { }
        });
    }

    private static void parseEvents(InputStream stream, Predicate<String> onEvent) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new BufferedReader(new InputStreamReader(stream, decoder));
        StringBuilder line = new StringBuilder(), data = new StringBuilder();
        boolean skipLf = false, first = true;
        int value;
        while ((value = reader.read()) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("流已中断");
            if (first) { first = false; if (value == '\uFEFF') continue; }
            if (skipLf) { skipLf = false; if (value == '\n') continue; }
            if (value == '\r' || value == '\n') {
                skipLf = value == '\r';
                if (!consumeLine(line.toString(), data, onEvent)) return;
                line.setLength(0);
            } else {
                if (line.length() >= MAX_EVENT_CHARS) throw new ProviderException("流式事件超过大小限制");
                line.append((char) value);
            }
        }
        // 没有空行分隔符的事件不完整，绝不能据此授权工具执行。
        if (!line.isEmpty() || !data.isEmpty()) throw new ProviderException("流式响应在事件内部结束");
    }

    private static boolean consumeLine(String line, StringBuilder data, Predicate<String> onEvent) {
        if (line.isEmpty()) {
            if (data.isEmpty()) return true;
            data.setLength(data.length() - 1);
            String event = data.toString();
            data.setLength(0);
            return onEvent.test(event);
        }
        if (line.startsWith(":")) return true;
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        if (!field.equals("data")) return true;
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ((long) data.length() + value.length() + 1 > MAX_EVENT_CHARS) {
            throw new ProviderException("流式事件超过大小限制");
        }
        data.append(value).append('\n');
        return true;
    }

    private static final class LimitedInput extends FilterInputStream {
        private long read;
        private LimitedInput(InputStream delegate) { super(delegate); }
        private void count(int size) {
            if (size > 0 && (read += size) > MAX_BODY_BYTES) throw new ProviderException("流式响应超过大小限制");
        }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) count(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int size = in.read(bytes, offset, length);
            count(size);
            return size;
        }
    }
}
