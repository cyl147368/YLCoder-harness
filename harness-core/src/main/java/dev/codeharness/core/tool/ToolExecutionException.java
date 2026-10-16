package dev.codeharness.core.tool;

public final class ToolExecutionException extends Exception {
    public ToolExecutionException(String message) { super(message); }
    public ToolExecutionException(String message, Throwable cause) { super(message, cause); }
}
