package dev.codeharness.cli;

import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.TokenUsage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 汇总真实请求的用量可用性；零值与缺失值分开，审核和主模型都纳入统计。 */
final class UsageDashboard {
    private final Counter process = new Counter();
    private Counter task = new Counter();

    synchronized void beginTask() { task = new Counter(); }
    synchronized Snapshot task() { return task.snapshot("current-task"); }
    synchronized Snapshot process() { return process.snapshot("current-process"); }

    /** 审核由等待结果的调用方记账，超时后迟到的后台响应不能更改已结束任务。 */
    synchronized void record(ChatResponse response) { start().finish(response); }

    ChatModel track(ChatModel delegate) {
        return new ChatModel() {
            @Override public ChatResponse complete(ChatRequest request) {
                Ticket ticket = start();
                try {
                    ChatResponse response = delegate.complete(request);
                    ticket.finish(response);
                    return response;
                } catch (RuntimeException | Error failure) { ticket.finish(null); throw failure; }
            }
            @Override public ChatResponse complete(ChatRequest request, Consumer<String> listener) {
                Ticket ticket = start();
                try {
                    ChatResponse response = delegate.complete(request, listener);
                    ticket.finish(response);
                    return response;
                } catch (RuntimeException | Error failure) { ticket.finish(null); throw failure; }
            }
        };
    }

    private synchronized Ticket start() {
        task.requests++;
        process.requests++;
        return new Ticket(task);
    }

    private final class Ticket {
        private final Counter owner;
        private final AtomicBoolean finished = new AtomicBoolean();
        Ticket(Counter owner) { this.owner = owner; }
        void finish(ChatResponse response) {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (UsageDashboard.this) {
                owner.add(response);
                process.add(response);
            }
        }
    }

    private static final class Counter {
        private int requests;
        private int inputs;
        private int outputs;
        private int caches;
        private int invalidCacheRequests;
        private long cacheSampleInput;
        private long cacheSampleHit;
        private TokenUsage total = TokenUsage.ZERO;

        private void add(ChatResponse response) {
            if (response == null) return;
            TokenUsage usage = response.usage();
            boolean input = reported(response, "usage.inputReported", usage.inputTokens());
            boolean output = reported(response, "usage.outputReported", usage.outputTokens());
            boolean cache = reported(response, "usage.cacheReported", usage.cachedInputTokens());
            total = total.plus(new TokenUsage(input ? usage.inputTokens() : 0,
                    output ? usage.outputTokens() : 0, cache ? usage.cachedInputTokens() : 0));
            if (input) inputs++;
            if (output) outputs++;
            if (cache) caches++;
            if (input && cache) {
                if (usage.cachedInputTokens() > usage.inputTokens()) invalidCacheRequests++;
                else {
                    cacheSampleInput += usage.inputTokens();
                    cacheSampleHit += usage.cachedInputTokens();
                }
            }
        }

        private Snapshot snapshot(String scope) {
            Double knownRate = cacheSampleInput > 0 ? 100.0 * cacheSampleHit / cacheSampleInput : null;
            boolean complete = requests > 0 && inputs == requests && caches == requests && invalidCacheRequests == 0;
            Double rate = complete && total.inputTokens() > 0
                    ? 100.0 * total.cachedInputTokens() / total.inputTokens() : null;
            return new Snapshot(requests, total, inputs, outputs, caches, rate, knownRate, scope);
        }
    }

    /** 外部 SPI 未提供可用性标记时只相信明确的正数，零值仍保守地视为未知。 */
    private static boolean reported(ChatResponse response, String name, long count) {
        String flag = response.providerMetadata().get(name);
        return flag == null ? count > 0 : "true".equals(flag);
    }

    record Snapshot(int requests, TokenUsage total, int inputReportedRequests, int outputReportedRequests,
                    int cacheReportedRequests, Double cacheHitRatePercent, Double reportedCacheHitRatePercent,
                    String scope) {
        static Snapshot empty(String scope) { return new Snapshot(0, TokenUsage.ZERO, 0, 0, 0, null, null, scope); }
    }
}
