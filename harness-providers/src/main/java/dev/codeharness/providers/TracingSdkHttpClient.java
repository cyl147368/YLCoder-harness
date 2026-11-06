package dev.codeharness.providers;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.sse.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

/** 在 SDK 序列化之后、SSE 解析之前观察正文；保留 SDK 的背压与取消行为。 */
final class TracingSdkHttpClient implements HttpClient {
    private final HttpClient delegate;
    private TracingSdkHttpClient(HttpClient delegate) { this.delegate = delegate; }

    static HttpClientBuilder builder() {
        return new HttpClientBuilder() {
            private final dev.langchain4j.http.client.jdk.JdkHttpClientBuilder delegate = JdkHttpClient.builder()
                    .httpClientBuilder(java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER));
            public Duration connectTimeout() { return delegate.connectTimeout(); }
            public HttpClientBuilder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
            public Duration readTimeout() { return delegate.readTimeout(); }
            public HttpClientBuilder readTimeout(Duration value) { delegate.readTimeout(value); return this; }
            public HttpClient build() { return new TracingSdkHttpClient(delegate.build()); }
        };
    }

    private static HttpExchangeTrace.Exchange start(HttpRequest request) {
        return HttpExchangeTrace.start(request.method().name(), URI.create(request.url()), request.body());
    }

    @Override public SuccessfulHttpResponse execute(HttpRequest request) {
        var trace = start(request);
        try {
            var response = delegate.execute(request);
            trace.headers(response.statusCode(), response.contentType());
            trace.accept(response.body());
            trace.finish("HTTP 正文读取结束");
            return response;
        } catch (RuntimeException failure) {
            failed(trace, failure);
            throw failure;
        } finally { trace.finish("响应读取未完成"); }
    }

    private static void failed(HttpExchangeTrace.Exchange trace, Throwable failure) {
        // JDK SDK 客户端将 HTTP 错误正文原样放入 HttpException；不记录其他异常的未知上下文。
        if (failure instanceof dev.langchain4j.exception.HttpException http) {
            trace.headers(http.statusCode(), null);
            trace.accept(http.getMessage());
            trace.finish("HTTP 错误正文读取结束（SDK 未保留响应类型）");
        } else trace.finish("SDK 响应读取未完成");
    }

    private static ServerSentEventParser parser(ServerSentEventParser delegate, HttpExchangeTrace.Exchange trace) {
        return new ServerSentEventParser() {
            public void parse(InputStream input, ServerSentEventListener listener) { delegate.parse(trace.capture(input), listener); }
            public Incremental incremental() {
                Incremental original = delegate.incremental();
                return new Incremental() {
                    public List<ServerSentEvent> feed(ByteBuffer buffer) { trace.accept(buffer); return original.feed(buffer); }
                    public List<ServerSentEvent> flush() { return original.flush(); }
                };
            }
        };
    }

    @Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        var trace = start(request);
        try {
            delegate.execute(request, parser(parser, trace), new ServerSentEventListener() {
                public void onOpen(SuccessfulHttpResponse response) { trace.headers(response.statusCode(), response.contentType()); listener.onOpen(response); }
                public void onEvent(ServerSentEvent event, ServerSentEventContext context) { listener.onEvent(event, context); }
                public void onError(Throwable failure) { failed(trace, failure); listener.onError(failure); }
                public void onClose() { trace.finish("HTTP 流读取结束"); listener.onClose(); }
            });
        } catch (RuntimeException failure) { failed(trace, failure); throw failure; }
    }

    @Override public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        var trace = start(request);
        return subscriber -> {
            try {
                delegate.stream(request, parser(parser, trace)).subscribe(new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription upstream) {
                        subscriber.onSubscribe(new Flow.Subscription() {
                            public void request(long n) { upstream.request(n); }
                            public void cancel() { trace.finish("订阅已取消；保留已收到的响应片段"); upstream.cancel(); }
                        });
                    }
                    public void onNext(HttpStreamingEvent event) {
                        if (event instanceof HttpResponseReceived received) trace.headers(received.response().statusCode(), received.response().contentType());
                        subscriber.onNext(event);
                    }
                    public void onError(Throwable failure) { failed(trace, failure); subscriber.onError(failure); }
                    public void onComplete() { trace.finish("HTTP 流读取结束"); subscriber.onComplete(); }
                });
            } catch (RuntimeException failure) { failed(trace, failure); throw failure; }
        };
    }
}
