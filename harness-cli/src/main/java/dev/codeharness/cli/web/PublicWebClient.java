package dev.codeharness.cli.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 独立无凭据的网页客户端；限制响应大小和时长，逐跳检查地址，不执行网页脚本。 */
final class PublicWebClient implements WebTools.Fetcher, AutoCloseable {
    private static final int MAX_BYTES = 2_000_000;
    private final Duration timeout;
    private final HttpClient client;

    PublicWebClient(int seconds) {
        timeout = Duration.ofSeconds(seconds);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public WebTools.Page get(URI uri, URI trustedSearch) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirects = 0; redirects <= 4; redirects++) {
            validateUri(uri);
            // 用户在可信配置中显式指定的搜索服务可以是自建内网服务；任意网页读取没有此例外。
            if (trustedSearch == null || !sameOrigin(uri, trustedSearch)) checkPublicHost(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("联网读取超时，请稍后重试");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", "Mozilla/5.0 (compatible; YLCoder/0.1; public-text-reader)")
                    .header("Accept", "text/html,application/xhtml+xml,application/rss+xml,application/json,text/plain;q=0.8")
                    .GET().build();
            var pending = client.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException cancelled) { pending.cancel(true); Thread.currentThread().interrupt(); throw cancelled; }
            catch (Exception failure) { pending.cancel(true); throw new IOException("联网读取失败、超时或响应超过 2 MB；请尝试其他来源"); }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String next = response.headers().firstValue("location").orElseThrow(() -> new IOException("网页重定向缺少地址"));
                try { uri = uri.resolve(next); }
                catch (IllegalArgumentException invalid) { throw new IOException("网页重定向地址无效"); }
                continue;
            }
            if (status != 200) throw new IOException("联网服务返回 HTTP " + status + "；请尝试其他来源");
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!(type.startsWith("text/") || type.contains("html") || type.contains("xml") || type.contains("json")))
                throw new IOException("仅支持网页和文本内容，不读取下载文件");
            return new WebTools.Page(uri, type, response.body());
        }
        throw new IOException("网页重定向次数过多");
    }

    static void validateUri(URI uri) throws IOException {
        if (uri == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.toString().length() > 4096)
            throw new IOException("网页地址必须是无内嵌凭据的 HTTP/HTTPS URL");
    }

    static void checkPublicHost(URI uri) throws IOException {
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
            throw new IOException("网页读取仅允许公网 HTTP/HTTPS 标准端口");
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!publicAddress(address)) throw new IOException("网页读取不访问本机、内网或保留地址");
            }
        } catch (java.net.UnknownHostException unknown) { throw new IOException("无法解析网页主机名"); }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]), second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 16) return (first & 0xfe) != 0xfc && first != 0 && !(first == 0x20 && second == 0x02);
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 192 && second == 0);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }

    @Override public void close() { client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new IOException("响应过大")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
