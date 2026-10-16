package dev.codeharness.cli.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 实际智能体执行带延迟的本地读取夹具；不作模型、磁盘或生产延迟主张。 */
public final class ToolExecutionBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int READS_PER_PHASE = 4;
    private static final int DEFAULT_DELAY_MS = 80;
    private static final int DEFAULT_SAMPLES = 5;
    private static final List<ToolCall> CALLS = calls();

    private ToolExecutionBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("用法：ToolExecutionBenchmark");
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(measure(DEFAULT_DELAY_MS, DEFAULT_SAMPLES)));
    }

    static Map<String, Object> measure(int delayMs, int samples) throws Exception {
        if (delayMs < 1 || samples < 3 || samples > 5) throw new IllegalArgumentException("延迟须为正数，样本数须为三到五");
        Map<Integer, List<Sample>> observations = new LinkedHashMap<>();
        observations.put(1, new ArrayList<>());
        observations.put(4, new ArrayList<>());
        // 两种执行模式各预热一次；每个测量样本使用新状态和相同任务。
        run(1, delayMs);
        run(4, delayMs);
        for (int sample = 0; sample < samples; sample++) {
            // 交替执行顺序，减少预热或时间顺序造成的系统性优势。
            for (int parallelism : sample % 2 == 0 ? List.of(1, 4) : List.of(4, 1)) {
                observations.get(parallelism).add(run(parallelism, delayMs));
            }
        }
        List<Map<String, Object>> variants = new ArrayList<>();
        for (var entry : observations.entrySet()) {
            List<Sample> rows = entry.getValue();
            double[] elapsed = rows.stream().mapToDouble(Sample::elapsedMs).sorted().toArray();
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("maxParallelReads", entry.getKey());
            variant.put("elapsedMs", Map.of("p50", percentile(elapsed, .50), "p95", percentile(elapsed, .95)));
            variant.put("samples", rows);
            variant.put("allOutputChecksPassed", rows.stream().allMatch(Sample::outputCorrect));
            variants.add(variant);
        }
        // 独立于计时和调度检查请求内容是否等价。
        Sample serial = observations.get(1).getFirst();
        for (List<Sample> rows : observations.values()) for (Sample row : rows) {
            if (!row.requestMessageCounts().equals(serial.requestMessageCounts())
                    || !row.requestSchemaCounts().equals(serial.requestSchemaCounts())
                    || row.serializedRequestBytes() != serial.serializedRequestBytes()) {
                throw new IllegalStateException("执行变体改变了模型请求载荷");
            }
        }
        return Map.of("benchmarkVersion", 1,
                "scope", "通过 CodeAgent 执行带延迟的模拟读取负载；不作网络、厂商延迟、磁盘速度或生产节省主张。",
                "environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name")),
                "method", Map.of("samplesPerVariant", samples, "warmupsPerVariant", 1, "readDelayMs", delayMs,
                        "readsBeforeMutation", READS_PER_PHASE, "readsAfterMutation", READS_PER_PHASE,
                        "mutations", 1, "percentile", "最近秩", "elapsedScope", "仅测量 CodeAgent.run；包括模拟等待"),
                "variants", variants);
    }

    private static Sample run(int parallelism, int delayMs) throws Exception {
        Fixture fixture = new Fixture(delayMs);
        var model = new ScriptedModel();
        var agent = new CodeAgent(model, new ToolRegistry(List.of(fixture.readTool(), fixture.mutationTool())));
        long started = System.nanoTime();
        AgentResult result = agent.run("读取四个值，将状态递增，然后再次读取四个值。",
                new AgentRunOptions("保持工具结果与调用顺序一致。", 3, 12_000, 128, 512, () -> false, parallelism));
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        if (result.status() != AgentResult.Status.COMPLETED || !"verified".equals(result.finalAnswer())
                || fixture.generation.get() != 1 || fixture.readsDone.get() != 8 || fixture.active.get() != 0
                || fixture.violation.get() != 0 || model.requests.size() != 2) {
            throw new IllegalStateException("执行正确性验证失败：" + result.status());
        }
        checkResults(result.transcript());
        int peak = fixture.peak.get();
        if (peak < 1 || peak > parallelism || (parallelism == 1 && peak != 1)) {
            throw new IllegalStateException("实际并发数超过配置上限");
        }
        int bytes = 0;
        for (ChatRequest request : model.requests) bytes += JSON.writeValueAsString(request).getBytes(StandardCharsets.UTF_8).length;
        return new Sample(elapsed, peak, peak > 1, true, fixture.generation.get(), fixture.readsDone.get(),
                model.requests.size(), model.requests.stream().map(r -> r.messages().size()).toList(),
                model.requests.stream().map(r -> r.tools().size()).toList(), bytes);
    }

    private static void checkResults(List<ChatMessage> transcript) {
        List<ChatMessage> results = transcript.stream().filter(m -> m.role() == ChatMessage.Role.TOOL).toList();
        if (results.size() != CALLS.size()) throw new IllegalStateException("缺少工具结果");
        for (int index = 0; index < CALLS.size(); index++) {
            ToolCall call = CALLS.get(index);
            String expected = index == READS_PER_PHASE ? "state=1" : "value=" + (index < READS_PER_PHASE ? 0 : 1);
            ChatMessage result = results.get(index);
            if (!call.id().equals(result.toolCallId()) || !call.name().equals(result.toolName()) || !expected.equals(result.content())) {
                throw new IllegalStateException("结果顺序错误或修改屏障失效");
            }
        }
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }

    private static List<ToolCall> calls() {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < READS_PER_PHASE; i++) calls.add(new ToolCall("before-" + i, "delayed_read", "{}"));
        calls.add(new ToolCall("mutate", "increment", "{}"));
        for (int i = 0; i < READS_PER_PHASE; i++) calls.add(new ToolCall("after-" + i, "delayed_read", "{}"));
        return List.copyOf(calls);
    }

    private static final class ScriptedModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse complete(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) return new ChatResponse("检查并更新。", CALLS, TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            if (requests.size() != 2) throw new IllegalStateException("模型请求不符合预期");
            checkResults(request.messages());
            return ChatResponse.text("verified");
        }
    }

    private static final class Fixture {
        private final int delayMs;
        private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), generation = new AtomicInteger();
        private final AtomicInteger readsDone = new AtomicInteger(), violation = new AtomicInteger();
        Fixture(int delayMs) { this.delayMs = delayMs; }

        Tool readTool() {
            return new Tool() {
                @Override public ToolDefinition definition() { return definitionFor("delayed_read", "经过固定的模拟等待后读取状态"); }
                @Override public boolean isParallelSafeRead() { return true; }
                @Override public String execute(String argumentsJson) throws InterruptedException {
                    int running = active.incrementAndGet();
                    peak.accumulateAndGet(running, Math::max);
                    int value = generation.get();
                    try {
                        Thread.sleep(delayMs);
                        if (generation.get() != value) violation.incrementAndGet();
                        readsDone.incrementAndGet();
                        return "value=" + value;
                    } finally { active.decrementAndGet(); }
                }
            };
        }

        Tool mutationTool() {
            return new Tool() {
                @Override public ToolDefinition definition() { return definitionFor("increment", "在两个读取阶段之间递增状态"); }
                @Override public String execute(String argumentsJson) {
                    if (active.get() != 0 || readsDone.get() != READS_PER_PHASE || generation.get() != 0) violation.incrementAndGet();
                    return "state=" + generation.incrementAndGet();
                }
            };
        }

        private static ToolDefinition definitionFor(String name, String description) {
            return new ToolDefinition(name, description, Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
        }
    }

    public record Sample(double elapsedMs, int maxActiveReads, boolean overlapObserved, boolean outputCorrect,
                         int finalState, int readCompletions, int modelRequests, List<Integer> requestMessageCounts,
                         List<Integer> requestSchemaCounts, int serializedRequestBytes) {}
}
