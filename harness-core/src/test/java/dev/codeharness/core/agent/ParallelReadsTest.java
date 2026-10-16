package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 基于门闩的调度验收，不涉及网络或性能阈值断言。 */
class ParallelReadsTest {
    @Test void resultCallbacksCancelBeforeFollowingMutationBarrier() {
        for (boolean checkpoint : List.of(false, true)) for (int readCount : List.of(1, 2)) {
            var cancelled = new AtomicBoolean();
            var mutations = new AtomicInteger();
            var mutationStarts = new AtomicInteger();
            var modelCalls = new AtomicInteger();
            var read = tool("read", true, arg -> "read");
            var mutate = tool("mutate", false, arg -> { mutations.incrementAndGet(); return "changed"; });
            var calls = new ArrayList<>(calls("read", readCount));
            calls.add(new ToolCall("mutate", "mutate", "{}"));
            var result = agent(List.of(read, mutate), calls, modelCalls).run("read", options(2, cancelled::get),
                    new AgentListener() {
                        @Override public void onCheckpoint(List<ChatMessage> history) {
                            if (checkpoint && toolIds(history).size() == readCount) cancelled.set(true);
                        }
                        @Override public void onToolResult(int iteration, ToolCall call, String output, boolean error) {
                            if (!checkpoint && call.id().equals("call-" + (readCount - 1))) cancelled.set(true);
                        }
                        @Override public void onToolStart(int iteration, ToolCall call) {
                            if (call.name().equals("mutate")) mutationStarts.incrementAndGet();
                        }
                    });
            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(1, modelCalls.get());
            assertEquals(0, mutations.get());
            assertEquals(0, mutationStarts.get());
            assertEquals(readCount, toolIds(result.transcript()).size());
        }
    }

    @Test void interruptedIoFailureInWorkerStopsLaterMutation() {
        var mutations = new AtomicInteger();
        var read = tool("read", true, arg -> {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Read interrupted");
        });
        var mutate = tool("mutate", false, arg -> { mutations.incrementAndGet(); return "changed"; });
        try {
            var result = agent(List.of(read, mutate), List.of(new ToolCall("read", "read", "{}"),
                    new ToolCall("mutate", "mutate", "{}")), new AtomicInteger()).run("read", options(2, () -> false));
            assertEquals(AgentResult.Status.CANCELLED, result.status());
            assertEquals(0, mutations.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void safeReadsOverlapWithinConfiguredCap() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var executions = new AtomicInteger();
        var tool = tool("read", true, arg -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            executions.incrementAndGet();
            entered.countDown();
            try { await(release); return arg; }
            finally { active.decrementAndGet(); }
        });
        var modelCalls = new AtomicInteger();
        var agent = agent(List.of(tool), calls("read", 6), modelCalls);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> agent.run("read", options(2, () -> false)));
            try {
                await(entered);
                assertEquals(2, active.get());
            } finally { release.countDown(); }
            var result = future.get(5, TimeUnit.SECONDS);
            assertEquals(AgentResult.Status.COMPLETED, result.status());
            assertEquals(6, executions.get());
            assertEquals(2, peak.get());
            assertEquals(2, modelCalls.get());
        }
    }

    @Test void mutationIsBarrierAndReversedReadsCommitInCallOrder() throws Exception {
        var started = new CountDownLatch(2);
        var secondCompleted = new CountDownLatch(1);
        var active = new AtomicInteger();
        var state = new AtomicInteger();
        var completionOrder = new CopyOnWriteArrayList<String>();
        var startsBeforeCheckpoint = new AtomicInteger();
        var assistantCheckpoint = new AtomicBoolean();
        var first = tool("first", true, arg -> {
            if (!assistantCheckpoint.get()) startsBeforeCheckpoint.incrementAndGet();
            active.incrementAndGet(); started.countDown();
            try { await(started); await(secondCompleted); completionOrder.add("first"); return "before=" + state.get(); }
            finally { active.decrementAndGet(); }
        });
        var second = tool("second", true, arg -> {
            if (!assistantCheckpoint.get()) startsBeforeCheckpoint.incrementAndGet();
            active.incrementAndGet(); started.countDown();
            try { await(started); completionOrder.add("second"); return "before=" + state.get(); }
            finally { active.decrementAndGet(); secondCompleted.countDown(); }
        });
        var mutation = tool("mutate", false, arg -> {
            assertEquals(0, active.get());
            assertEquals(List.of("second", "first"), completionOrder);
            state.set(1); return "changed";
        });
        var after = tool("after", true, arg -> { assertEquals(1, state.get()); return "after=1"; });
        var expectedIds = List.of("first", "second", "mutate", "after");
        var calls = expectedIds.stream().map(name -> new ToolCall(name, name, "{}")).toList();
        var checkpoints = new ArrayList<List<String>>();
        var callbackThreads = new ArrayList<Thread>();
        Thread coordinator = Thread.currentThread();
        var listener = new AgentListener() {
            @Override public void onCheckpoint(List<ChatMessage> history) {
                callbackThreads.add(Thread.currentThread());
                if (history.stream().anyMatch(m -> !m.toolCalls().isEmpty())) assistantCheckpoint.set(true);
                var ids = toolIds(history);
                if (!ids.isEmpty() && (checkpoints.isEmpty() || !checkpoints.getLast().equals(ids))) checkpoints.add(ids);
            }
        };
        var result = agent(List.of(first, second, mutation, after), calls, new AtomicInteger())
                .run("read then mutate", options(2, () -> false), listener);
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(expectedIds, toolIds(result.transcript()));
        assertEquals(List.of(List.of("first"), List.of("first", "second"), List.of("first", "second", "mutate"), expectedIds), checkpoints);
        assertTrue(callbackThreads.stream().allMatch(thread -> thread == coordinator));
        assertEquals(0, startsBeforeCheckpoint.get());
    }

    @Test void defaultsAndUnclassifiedToolsRemainSerial() throws Exception {
        assertEquals(1, AgentRunOptions.defaults().maxParallelReads());
        assertEquals(1, new AgentRunOptions("test", 3, 1000, 100, 1000, () -> false).maxParallelReads());
        for (boolean defaultOptions : List.of(true, false)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var secondEntered = new CountDownLatch(1);
            var count = new AtomicInteger();
            Operation operation = arg -> {
                if (count.incrementAndGet() == 1) { entered.countDown(); await(release); }
                else secondEntered.countDown();
                return arg;
            };
            Tool target = defaultOptions ? tool("read", true, operation) : new Tool() {
                @Override public ToolDefinition definition() { return definitionFor("read"); }
                @Override public String execute(String arg) throws Exception { return operation.run(arg); }
            };
            if (!defaultOptions) assertFalse(target.isParallelSafeRead());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit(() -> agent(List.of(target), calls("read", 2), new AtomicInteger())
                        .run("read", defaultOptions ? AgentRunOptions.defaults() : options(4, () -> false)));
                try {
                    await(entered);
                    assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS), "串行工具与被保持的首次调用发生重叠");
                } finally { release.countDown(); }
                assertEquals(AgentResult.Status.COMPLETED, future.get(5, TimeUnit.SECONDS).status());
                assertEquals(2, count.get());
            }
        }
    }

    @Test void checkpointFailureInterruptsReadsAndPreventsLaterMutation() throws Exception {
        var started = new CountDownLatch(2);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var mutations = new AtomicInteger();
        var first = tool("first", true, arg -> { started.countDown(); await(started); return "first"; });
        var held = tool("held", true, arg -> {
            started.countDown();
            try { await(release); return "held"; }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
        });
        var mutation = tool("mutate", false, arg -> { mutations.incrementAndGet(); return "changed"; });
        var modelCalls = new AtomicInteger();
        try {
            var result = agent(List.of(first, held, mutation), List.of(new ToolCall("a", "first", "{}"),
                    new ToolCall("b", "held", "{}"), new ToolCall("c", "mutate", "{}")), modelCalls)
                    .run("read", options(2, () -> false), new AgentListener() {
                        @Override public void onCheckpoint(List<ChatMessage> history) {
                            if (history.stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL)) throw new IllegalStateException("checkpoint failed");
                        }
                    });
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertTrue(result.error().contains("checkpoint failed"));
            await(interrupted);
            assertEquals(0, mutations.get());
            assertEquals(1, modelCalls.get());
        } finally { release.countDown(); }
    }

    @Test void cancellationDuringWaitInterruptsReadsAndPreventsLaterToolsAndModel() throws Exception {
        var started = new CountDownLatch(2);
        var interrupted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var mutations = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var read = tool("read", true, arg -> {
            started.countDown();
            try { await(release); return "read"; }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
        });
        var mutate = tool("mutate", false, arg -> { mutations.incrementAndGet(); return "changed"; });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> agent(List.of(read, mutate), List.of(new ToolCall("a", "read", "{}"),
                    new ToolCall("b", "read", "{}"), new ToolCall("c", "mutate", "{}")), modelCalls)
                    .run("read", options(2, cancelled::get)));
            try {
                await(started); cancelled.set(true);
                assertEquals(AgentResult.Status.CANCELLED, future.get(5, TimeUnit.SECONDS).status());
                await(interrupted);
                assertEquals(0, mutations.get());
                assertEquals(1, modelCalls.get());
            } finally { release.countDown(); }
        }
    }

    @Test void maxIterationDoesNotExecuteParallelCalls() {
        var executed = new AtomicInteger();
        var read = tool("read", true, arg -> { executed.incrementAndGet(); return "read"; });
        var result = agent(List.of(read), calls("read", 3), new AtomicInteger()).run("read",
                new AgentRunOptions("test", 1, 12000, 100, 1000, () -> false, 4));
        assertEquals(AgentResult.Status.MAX_ITERATIONS, result.status());
        assertEquals(0, executed.get());
    }

    @Test void failedAssistantCheckpointStartsNoReads() {
        var executed = new AtomicInteger();
        var read = tool("read", true, arg -> { executed.incrementAndGet(); return "read"; });
        var result = agent(List.of(read), calls("read", 2), new AtomicInteger())
                .run("read", options(2, () -> false), new AgentListener() {
                    @Override public void onCheckpoint(List<ChatMessage> history) {
                        if (history.stream().anyMatch(message -> !message.toolCalls().isEmpty())) {
                            throw new IllegalStateException("assistant checkpoint failed");
                        }
                    }
                });
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(0, executed.get());
    }

    @Test void cancellationReturnsWithoutWaitingForUncooperativeExtension() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var read = tool("uncooperative", true, arg -> {
            started.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return "late read";
            } finally { stopped.countDown(); }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> agent(List.of(read), calls("uncooperative", 1), new AtomicInteger())
                    .run("read", options(2, cancelled::get)));
            try {
                await(started); cancelled.set(true);
                assertEquals(AgentResult.Status.CANCELLED, future.get(5, TimeUnit.SECONDS).status());
                await(interrupted);
                assertEquals(1, stopped.getCount(), "刻意保持的扩展应该仍在运行");
            } finally { release.countDown(); }
            await(stopped);
        }
    }

    private static AgentRunOptions options(int parallelism, BooleanSupplier cancelled) {
        return new AgentRunOptions("test", 4, 12000, 100, 1000, cancelled, parallelism);
    }
    private static CodeAgent agent(List<Tool> tools, List<ToolCall> calls, AtomicInteger modelCalls) {
        ChatModel model = request -> modelCalls.getAndIncrement() == 0
                ? new ChatResponse("reading", calls, TokenUsage.ZERO, FinishReason.TOOL_CALLS) : ChatResponse.text("done");
        return new CodeAgent(model, new ToolRegistry(tools));
    }
    private static List<ToolCall> calls(String name, int count) {
        var calls = new ArrayList<ToolCall>();
        for (int i = 0; i < count; i++) calls.add(new ToolCall("call-" + i, name, "{\"index\":" + i + "}"));
        return calls;
    }
    private static List<String> toolIds(List<ChatMessage> messages) {
        return messages.stream().filter(m -> m.role() == ChatMessage.Role.TOOL).map(ChatMessage::toolCallId).toList();
    }
    private static ToolDefinition definitionFor(String name) { return new ToolDefinition(name, name, Map.of("type", "object")); }
    private static Tool tool(String name, boolean safe, Operation operation) {
        return new Tool() {
            @Override public ToolDefinition definition() { return definitionFor(name); }
            @Override public boolean isParallelSafeRead() { return safe; }
            @Override public String execute(String arg) throws Exception { return operation.run(arg); }
        };
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "等待调度器协调超时");
    }
    @FunctionalInterface private interface Operation { String run(String arguments) throws Exception; }
}
