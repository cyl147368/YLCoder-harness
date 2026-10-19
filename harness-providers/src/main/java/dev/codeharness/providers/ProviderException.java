package dev.codeharness.providers;

/** 安全、厂商无关的错误，刻意不包含请求头或凭据。 */
public final class ProviderException extends RuntimeException {
    private final int statusCode;
    private final boolean streamFallbackSuggested;

    static ProviderException streamFailure(String message) {
        return new ProviderException(message, 0, null, true);
    }

    /** 标识可尝试非流式的兼容错误；是否重试及次数由调用方策略决定。 */
    public boolean streamFallbackSuggested() { return streamFallbackSuggested; }

    public ProviderException(String message) {
        this(message, 0, null);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public ProviderException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    private ProviderException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, cause, false);
    }

    private ProviderException(String message, int statusCode, Throwable cause, boolean streamFallbackSuggested) {
        super(message, cause);
        this.statusCode = statusCode;
        this.streamFallbackSuggested = streamFallbackSuggested;
    }

    public int statusCode() { return statusCode; }
}
