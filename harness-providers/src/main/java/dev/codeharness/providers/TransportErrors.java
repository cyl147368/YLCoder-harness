package dev.codeharness.providers;

import com.fasterxml.jackson.core.JsonProcessingException;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** 传输异常只输出固定原因和类型，不传播可能包含地址、请求体或凭据的原异常。 */
final class TransportErrors {
    private TransportErrors() {}

    static ProviderException stream(Throwable failure) {
        return failure("流式请求", failure, true);
    }

    static ProviderException request(String provider, Throwable failure) {
        return failure(provider + " 请求", failure, false);
    }

    static ProviderException streamTimeout(Duration timeout) {
        return ProviderException.streamFailure("流式请求超时（TimeoutException）：超过配置的总时限 "
                + timeout.toMillis() + " 毫秒；请检查网络、代理或厂商响应速度，必要时增大 timeout-seconds");
    }

    private static ProviderException failure(String operation, Throwable failure, boolean streaming) {
        Kind selected = Kind.UNKNOWN;
        Throwable representative = failure;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        // HttpClient 常把连接、DNS 或 EOF 包在 IOException 中；只检查有界的类型链。
        for (Throwable cause = failure; cause != null && seen.size() < 16 && seen.add(cause); cause = cause.getCause()) {
            Kind kind = kind(cause);
            if (kind.ordinal() < selected.ordinal()) {
                selected = kind;
                representative = cause;
            }
        }
        String type = representative == null ? "UnknownException" : representative.getClass().getSimpleName();
        if (!type.matches("[A-Za-z0-9_$]{1,80}")) type = "UnknownException";
        String message = operation + "失败：" + selected.detail + "（" + type + "）";
        return streaming && selected.retryStream
                ? ProviderException.streamFailure(message) : new ProviderException(message);
    }

    private static Kind kind(Throwable failure) {
        if (failure instanceof InterruptedException || failure instanceof CancellationException
                || failure instanceof ClosedByInterruptException) return Kind.INTERRUPTED;
        if (failure instanceof HttpConnectTimeoutException) return Kind.CONNECT_TIMEOUT;
        if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException
                || failure instanceof TimeoutException) return Kind.TIMEOUT;
        if (failure instanceof InterruptedIOException) return Kind.INTERRUPTED;
        if (failure instanceof UnknownHostException || failure instanceof UnresolvedAddressException) return Kind.DNS;
        if (failure instanceof SSLException || failure instanceof java.security.cert.CertificateException) return Kind.TLS;
        if (failure instanceof JsonProcessingException || failure instanceof CharacterCodingException) return Kind.INVALID_RESPONSE;
        if (failure instanceof IllegalArgumentException || failure instanceof SecurityException) return Kind.INVALID_REQUEST;
        if (failure instanceof NoRouteToHostException) return Kind.NO_ROUTE;
        if (failure instanceof ConnectException) return Kind.CONNECT;
        if (failure instanceof EOFException) return Kind.CLOSED;
        if (failure instanceof ProtocolException) return Kind.PROTOCOL;
        if (failure instanceof SocketException) return Kind.SOCKET;
        if (failure instanceof IOException) return Kind.IO;
        return Kind.UNKNOWN;
    }

    // 顺序表示诊断优先级：具体原因优先于外层 IOException，取消始终禁止自动重试。
    private enum Kind {
        INTERRUPTED(false, "请求已中断或取消"),
        CONNECT_TIMEOUT(true, "连接厂商超时；请检查网络、代理和厂商服务状态"),
        TIMEOUT(true, "等待厂商响应超时；请检查网络或增大 timeout-seconds"),
        DNS(true, "无法解析厂商或代理的域名；请检查 base-url、DNS 和代理配置"),
        TLS(false, "TLS 握手或证书校验失败；请检查证书链、系统时间和代理配置"),
        INVALID_RESPONSE(false, "响应编码或 JSON 格式无效"),
        INVALID_REQUEST(false, "请求或客户端配置无效；请检查地址、请求头及本机访问限制"),
        NO_ROUTE(true, "无法连接厂商或代理，网络路由不可达"),
        CONNECT(true, "无法建立连接或连接被拒绝；请检查厂商地址、端口、网络及代理"),
        CLOSED(true, "连接提前关闭，未收到完整响应；请检查网络、代理或厂商服务状态"),
        PROTOCOL(true, "HTTP 传输协议异常；请检查厂商网关和代理"),
        SOCKET(true, "网络连接断开或被重置；请检查网络、代理或厂商服务状态"),
        IO(true, "网络读写失败，未收到完整响应；请检查网络、代理或厂商服务状态"),
        UNKNOWN(false, "客户端处理异常");

        private final boolean retryStream;
        private final String detail;
        Kind(boolean retryStream, String detail) {
            this.retryStream = retryStream;
            this.detail = detail;
        }
    }
}
