package dev.codeharness.providers;

/** 安全、厂商无关的错误，刻意不包含请求头或凭据。 */
public final class ProviderException extends RuntimeException {
    private final int statusCode;

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
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
