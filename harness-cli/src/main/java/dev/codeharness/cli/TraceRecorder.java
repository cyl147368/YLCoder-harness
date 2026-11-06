package dev.codeharness.cli;

import dev.codeharness.cli.trace.ExecutionTrace;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.providers.HttpExchangeTrace;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 将实际适配器调用和工具事件关联到任务；审核线程继承原任务，迟到回复不会串入下一轮。 */
final class TraceRecorder {
    private final Path workspace;
    private final HarnessConfig.Trace config;
    private final Consumer<String> warning;
    private final Set<String> secrets = new LinkedHashSet<>();
    private final InheritableThreadLocal<Capture> active = new InheritableThreadLocal<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Path latestPath;

    TraceRecorder(Path workspace, HarnessConfig.Trace config, Collection<String> secrets, Consumer<String> warning) {
        this.workspace = workspace;
        this.config = config;
        this.warning = warning;
        addSecrets(secrets);
    }

    synchronized void addSecrets(Collection<String> values) {
        if (values != null) values.stream().filter(value -> value != null && !value.isBlank()).forEach(secrets::add);
        Capture capture = active.get();
        if (capture != null) capture.log.addSecrets(secrets);
    }

    synchronized void begin(String session) {
        latestPath = null;
        Capture previous = active.get();
        if (previous != null) previous.finish("INTERRUPTED", "上一任务未正常结束");
        ExecutionTrace log = config.enabled ? ExecutionTrace.open(workspace, session, config.toolResultMaxChars,
                Set.copyOf(secrets), message -> {
                    if (warned.compareAndSet(false, true)) warning.accept(message);
                }) : ExecutionTrace.disabled();
        active.set(new Capture(log));
    }

    void iteration(int iteration) {
        Capture capture = active.get();
        if (capture != null) capture.iteration.set(iteration);
    }

    void context(dev.codeharness.core.agent.ContextMetrics metrics) {
        Capture capture = active.get();
        if (capture != null) capture.log.context(metrics);
    }

    void toolStart(int iteration, ToolCall call) {
        Capture capture = active.get();
        if (capture != null) capture.log.toolStart(iteration, call);
    }

    void toolResult(int iteration, ToolCall call, String result, boolean error) {
        Capture capture = active.get();
        if (capture != null) capture.log.toolResult(iteration, call, result, error);
    }

    void finish(String status, String error) {
        Capture capture = active.get();
        if (capture == null) return;
        try {
            capture.finish(status, error);
            latestPath = capture.log.path();
        } finally { active.remove(); }
    }

    Path latestPath() { return latestPath; }

    /** 放在流式兼容重试的内部，使每次重发同一提示词也有独立请求编号。 */
    ChatModel wrap(ChatModel delegate, String source, String provider, String model) {
        if (!config.enabled) return delegate;
        return new ChatModel() {
            @Override public ChatResponse complete(ChatRequest request) { return invoke(request, null, false); }
            @Override public ChatResponse complete(ChatRequest request, Consumer<String> listener) {
                return invoke(request, listener, true);
            }

            private ChatResponse invoke(ChatRequest request, Consumer<String> listener, boolean streaming) {
                Capture capture = active.get();
                if (capture == null) return streaming ? delegate.complete(request, listener) : delegate.complete(request);
                capture.retain();
                long id = capture.log.request(source, provider, model, streaming, capture.iteration.get(), request, false);
                long started = System.nanoTime();
                StringBuffer partial = new StringBuffer();
                AtomicBoolean truncated = new AtomicBoolean();
                var wire = HttpExchangeTrace.observe(new HttpExchangeTrace.Listener() {
                    public void request(int attempt, String method, String endpoint, String body) {
                        capture.log.wireRequest(id, attempt, method, endpoint, body);
                    }
                    public void response(int attempt, int status, String contentType, String body, boolean truncated, String state) {
                        capture.log.wireResponse(id, attempt, status, contentType, body, truncated, state);
                    }
                });
                try (wire) {
                    ChatResponse response = streaming ? delegate.complete(request, text -> {
                        if (text != null) {
                            synchronized (partial) {
                                int count = Math.min(text.length(), Math.max(0, config.toolResultMaxChars - partial.length()));
                                if (count > 0) partial.append(text, 0, count);
                                if (count < text.length()) truncated.set(true);
                            }
                        }
                        if (listener != null) listener.accept(text);
                    }) : delegate.complete(request);
                    if (!wire.observed()) capture.log.logicalFallback(id, request);
                    capture.log.response(id, response, elapsed(started), !wire.observed());
                    return response;
                } catch (RuntimeException | Error failure) {
                    if (!wire.observed()) capture.log.logicalFallback(id, request);
                    if (!wire.observed() && !partial.isEmpty()) capture.log.partial(id, partial + (truncated.get() ? "\n[部分回复摘要已截断]" : ""));
                    capture.log.failure(id, failure, elapsed(started));
                    throw failure;
                } finally { capture.release(); }
            }
        };
    }

    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000); }

    private static final class Capture {
        private final ExecutionTrace log;
        private final AtomicInteger iteration = new AtomicInteger();
        private int pending;
        private boolean ended;
        Capture(ExecutionTrace log) { this.log = log; }
        synchronized void retain() { pending++; }
        synchronized void release() { if (--pending == 0 && ended) log.close(); }
        synchronized void finish(String status, String error) {
            if (ended) return;
            ended = true;
            log.finish(status, error);
            if (pending == 0) log.close();
        }
    }
}
