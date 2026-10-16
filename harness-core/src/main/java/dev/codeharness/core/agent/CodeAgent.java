package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 厂商无关的编码代理循环，通过明确的本地注册表执行模型工具调用。 */
public final class CodeAgent {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final ContextCompactor contextCompactor;

    public CodeAgent(ChatModel model, ToolRegistry tools) {
        this(model, tools, new ContextCompactor());
    }

    public CodeAgent(ChatModel model, ToolRegistry tools, ContextCompactor contextCompactor) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.contextCompactor = Objects.requireNonNull(contextCompactor, "contextCompactor");
    }

    public AgentResult run(String task, AgentRunOptions options) {
        return run(task, options, AgentListener.NOOP);
    }

    public AgentResult run(String task, AgentRunOptions options, AgentListener listener) {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("任务不能为空");
        Objects.requireNonNull(options, "options");
        List<ChatMessage> initial = new ArrayList<>();
        if (!options.systemPrompt().isBlank()) initial.add(ChatMessage.system(options.systemPrompt()));
        initial.add(ChatMessage.user(task));
        return resume(initial, options, listener);
    }

    /** 继续已有会话；未得到响应的工具标记为中断，绝不自动重放。 */
    public AgentResult resume(List<ChatMessage> history, AgentRunOptions options, AgentListener listener) {
        Objects.requireNonNull(options, "options");
        if (history == null || history.isEmpty()) throw new IllegalArgumentException("历史记录不能为空");
        AgentListener observer = listener == null ? AgentListener.NOOP : listener;
        long started = System.nanoTime();
        List<ChatMessage> transcript = new ArrayList<>(history);
        TokenUsage usage = TokenUsage.ZERO;
        String lastText = "";
        int iterations = 0;
        AgentResult.Status status = AgentResult.Status.FAILED;
        String error = "";

        try {
            transcript = repairInterruptedTools(history);
            observer.onCheckpoint(List.copyOf(transcript));
            int schemaTokens = contextCompactor.estimateToolTokens(tools.definitions());
            int messageBudget = options.contextTokenBudget() - schemaTokens;
            if (messageBudget < 1) throw new IllegalArgumentException("工具结构超过配置的输入上下文预算");
            for (int iteration = 1; iteration <= options.maxIterations(); iteration++) {
                if (options.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    status = AgentResult.Status.CANCELLED;
                    break;
                }
                transcript = new ArrayList<>(contextCompactor.compact(transcript, messageBudget, options.maxToolOutputChars()));
                final int currentIteration = iteration;
                ChatRequest request = new ChatRequest(transcript, tools.definitions(), options.maxOutputTokens());
                notifySafely(() -> observer.onModelRequest(currentIteration, request));
                if (cancelled(options)) {
                    status = AgentResult.Status.CANCELLED;
                    break;
                }
                ChatResponse response = Objects.requireNonNull(model.complete(request,
                        delta -> notifySafely(() -> observer.onTextDelta(delta))), "模型返回了空响应");
                iterations = iteration;
                usage = usage.plus(response.usage());
                lastText = response.text();
                notifySafely(() -> observer.onModelResponse(currentIteration, response));
                ToolHistory.validateCalls(response.toolCalls());
                transcript.add(ChatMessage.assistantToolCalls(response.text(), response.toolCalls(), response.providerMetadata()));
                observer.onCheckpoint(List.copyOf(transcript));
                if (cancelled(options)) {
                    status = AgentResult.Status.CANCELLED;
                    break;
                }
                if (response.finishReason() == FinishReason.LENGTH) {
                    status = AgentResult.Status.FAILED;
                    error = "模型输出被截断；请增大 max-output-tokens 后恢复任务。";
                    break;
                }
                if (response.finishReason() == FinishReason.CONTENT_FILTER) {
                    status = AgentResult.Status.FAILED;
                    error = "厂商因内容过滤而停止生成。";
                    break;
                }
                if (response.finishReason() == FinishReason.OTHER) {
                    status = AgentResult.Status.FAILED;
                    error = "厂商返回了无法识别的结束原因，任务尚未完成。";
                    break;
                }
                if (response.toolCalls().isEmpty()) {
                    if (response.finishReason() != FinishReason.STOP) {
                        status = AgentResult.Status.FAILED;
                        error = "厂商声明有工具调用，但未返回实际调用。";
                    } else {
                        notifySafely(() -> observer.onText(response.text()));
                        status = AgentResult.Status.COMPLETED;
                    }
                    break;
                }
                if (iteration == options.maxIterations()) {
                    status = AgentResult.Status.MAX_ITERATIONS;
                    break;
                }
                if (!executeTools(response.toolCalls(), transcript, options, observer, currentIteration)) {
                    status = AgentResult.Status.CANCELLED;
                }
                if (status == AgentResult.Status.CANCELLED) break;
            }
            if (iterations == 0 && status == AgentResult.Status.FAILED && error.isBlank()) status = AgentResult.Status.CANCELLED;
        } catch (Exception failure) {
            status = Thread.currentThread().isInterrupted() ? AgentResult.Status.CANCELLED : AgentResult.Status.FAILED;
            String reason = failure.getMessage();
            error = reason == null || reason.isBlank() ? failure.getClass().getSimpleName() : reason;
        }
        return new AgentResult(status, lastText, error, iterations, usage,
                Duration.ofNanos(Math.max(0, System.nanoTime() - started)), transcript);
    }

    private void notifySafely(Runnable callback) {
        try { callback.run(); } catch (RuntimeException ignored) { /* 进度报告不能打断任务执行。 */ }
    }

    /** 仅连续且明确标记为安全的读取可以并行；其余每个调用都是严格屏障。 */
    private boolean executeTools(List<ToolCall> calls, List<ChatMessage> transcript, AgentRunOptions options,
                                 AgentListener observer, int iteration) throws Exception {
        for (int index = 0; index < calls.size();) {
            if (cancelled(options)) return false;
            ToolCall call = calls.get(index);
            Tool target = tools.find(call.name()).orElse(null);
            if (options.maxParallelReads() > 1 && target != null && target.isParallelSafeRead()) {
                int end = index + 1;
                while (end < calls.size() && end - index < options.maxParallelReads()) {
                    Tool next = tools.find(calls.get(end).name()).orElse(null);
                    if (next == null || !next.isParallelSafeRead()) break;
                    end++;
                }
                if (!executeReadBatch(calls.subList(index, end), transcript, options, observer, iteration)) return false;
                index = end;
            } else {
                notifySafely(() -> observer.onToolStart(iteration, call));
                if (cancelled(options)) return false;
                ToolResult result = executeTool(call, target, options.maxToolOutputChars());
                recordResult(call, result, transcript, observer, iteration);
                if (result.interrupted()) return false;
                index++;
            }
        }
        return !cancelled(options);
    }

    private boolean executeReadBatch(List<ToolCall> calls, List<ChatMessage> transcript, AgentRunOptions options,
                                     AgentListener observer, int iteration) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size(), Thread.ofVirtual().name("harness-read-", 0).factory());
        List<Future<ToolResult>> pending = new ArrayList<>();
        try {
            for (ToolCall call : calls) {
                if (cancelled(options)) return false;
                notifySafely(() -> observer.onToolStart(iteration, call));
                if (cancelled(options)) return false;
                Tool target = tools.find(call.name()).orElseThrow();
                pending.add(executor.submit(() -> executeTool(call, target, options.maxToolOutputChars())));
            }
            // 按调用顺序发布结果，使持久化的部分检查点保持确定性。
            for (int index = 0; index < calls.size(); index++) {
                ToolResult result = awaitRead(pending.get(index), options);
                if (result == null) return false;
                recordResult(calls.get(index), result, transcript, observer, iteration);
                if (result.interrupted()) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !cancelled(options);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            for (Future<ToolResult> future : pending) if (!future.isDone()) future.cancel(true);
            // close() 会无限等待不守约的扩展，取消操作不能这样等待。
            // 虚拟读取工作线程是守护线程；协作式工具在中断时结束。
            executor.shutdownNow();
        }
    }

    private static ToolResult awaitRead(Future<ToolResult> future, AgentRunOptions options) throws Exception {
        while (!cancelled(options)) {
            try {
                return future.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // 即使当前读取仍阻塞，也轮询用户取消请求。
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Error error) throw error;
                if (failed.getCause() instanceof Exception cause) throw cause;
                throw new IllegalStateException("并行读取失败");
            }
        }
        return null;
    }

    private static boolean cancelled(AgentRunOptions options) {
        return Thread.currentThread().isInterrupted() || options.cancelled().getAsBoolean();
    }

    private static ToolResult executeTool(ToolCall call, Tool target, int limit) {
        boolean failed = target == null;
        boolean interrupted = false;
        String output;
        if (target == null) {
            output = "工具不可用：" + call.name();
        } else {
            try {
                output = Objects.requireNonNullElse(target.execute(call.argumentsJson()), "");
                interrupted = Thread.currentThread().isInterrupted();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                interrupted = true;
                failed = true;
                output = "工具执行已中断，实际影响未知。重试前请检查当前状态。";
            } catch (Exception toolFailure) {
                failed = true;
                String reason = toolFailure.getMessage();
                output = "工具失败：" + (reason == null || reason.isBlank() ? toolFailure.getClass().getSimpleName() : reason);
            }
        }
        return new ToolResult(ContextCompactor.clipMiddle(output, limit), failed,
                interrupted || Thread.currentThread().isInterrupted());
    }

    private void recordResult(ToolCall call, ToolResult result, List<ChatMessage> transcript,
                              AgentListener observer, int iteration) {
        transcript.add(ChatMessage.toolResult(call, result.output()));
        observer.onCheckpoint(List.copyOf(transcript));
        notifySafely(() -> observer.onToolResult(iteration, call, result.output(), result.failed()));
    }

    private record ToolResult(String output, boolean failed, boolean interrupted) {}

    private static List<ChatMessage> repairInterruptedTools(List<ChatMessage> history) {
        ToolHistory.validate(history, true);
        List<ChatMessage> repaired = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            repaired.add(message);
            if (message.toolCalls().isEmpty()) continue;
            var results = new java.util.LinkedHashMap<String, ChatMessage>();
            while (i + 1 < history.size() && history.get(i + 1).role() == ChatMessage.Role.TOOL) {
                ChatMessage result = history.get(++i);
                results.put(result.toolCallId(), result);
            }
            for (ToolCall call : message.toolCalls()) {
                repaired.add(results.getOrDefault(call.id(), ChatMessage.toolResult(call,
                        "保存持久结果之前操作已中断，实际影响未知。重试此操作前请检查当前状态。")));
            }
        }
        return repaired;
    }
}
