package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 独立于完整会话的请求投影，保留近期轮次并持久化摘要边界。每次摘要只读取原历史。 */
public final class ContextManager {
    private final ContextCompactor compactor;
    private final ContextPolicy policy;
    private final ContextSummarizer summarizer;
    private ContextSnapshot snapshot;
    private List<ChatMessage> prefix = List.of();
    private int retryAt;
    private int consecutiveFailures;
    /** Tool call ids whose results were replaced by placeholders; once cleared they stay cleared so the request prefix never flips back. */
    private final java.util.Set<String> cleared = new java.util.HashSet<>();
    /** After this many consecutive summary failures, stop paying for summaries and rely on local trimming only. */
    static final int MAX_SUMMARY_FAILURES = 3;
    /** Upper bound on the extra headroom kept below the trigger so the summary request itself still fits. */
    static final int SUMMARY_BUFFER_TOKENS = 13_000;

    public ContextManager(ContextCompactor compactor, ContextPolicy policy, ContextSummarizer summarizer) {
        this.compactor = Objects.requireNonNull(compactor);
        this.policy = Objects.requireNonNull(policy);
        this.summarizer = summarizer;
    }
    public void restore(ContextSnapshot value) { snapshot = value; prefix = List.of(); priorOriginal = null; retryAt = 0; consecutiveFailures = 0; cleared.clear(); }

    /** Trigger = min(ratio of budget, budget - reserved summary output - buffer); never below 1. */
    int triggerTokens(int budget) {
        int byRatio = (int) (budget * policy.triggerRatio());
        int byReserve = budget - policy.summaryMaxTokens() - Math.min(SUMMARY_BUFFER_TOKENS, budget / 10);
        return Math.max(1, Math.min(byRatio, byReserve));
    }
    /** Warning tier sits one buffer below the trigger. */
    int warningTokens(int budget) {
        int trigger = triggerTokens(budget);
        return Math.max(1, trigger - Math.min(SUMMARY_BUFFER_TOKENS, budget / 10));
    }
    int consecutiveFailures() { return consecutiveFailures; }

    public Projection project(List<ChatMessage> original, int budget, int toolLimit) {
        long started = System.nanoTime();
        ToolHistory.validate(original, false);
        boolean reused = valid(original);
        if (snapshot != null && !reused) { snapshot = null; prefix = List.of(); }
        List<ChatMessage> working = projection(original);
        int before = compactor.estimateTokens(working);
        // 先应用每条工具的既有输出上限，避免按根本不会发送的大结果触发付费摘要。
        working = compactor.limitToolOutputs(working, toolLimit);
        // Free, local cleanup, but only once the context nears the threshold. Below the warning line history is left
        // untouched so the prompt cache keeps hitting; when it fires it clears everything except the most recent results
        // in one go, and already-cleared results are re-applied identically on every later request.
        if (policy.semantic() && compactor.estimateTokens(working) > warningTokens(budget)) advanceCleared(working);
        working = microCompact(working, cleared);
        TokenUsage usage = TokenUsage.ZERO;
        int boundary = snapshot == null ? 0 : snapshot.boundary();
        String reason = reused ? "复用已保存摘要及稳定请求前缀" : "未达到压缩阈值";
        boolean degraded = false;
        int workingTokens = compactor.estimateTokens(working);
        if (policy.semantic() && workingTokens > warningTokens(budget) && workingTokens <= triggerTokens(budget))
            reason = reason + "; context near compaction threshold (" + workingTokens + "/" + triggerTokens(budget) + ")";
        if (policy.semantic() && workingTokens > triggerTokens(budget)) {
            int nextBoundary = boundary(compactor.limitToolOutputs(original, toolLimit), budget);
            if (summarizer != null && nextBoundary > boundary && original.size() >= retryAt
                    && consecutiveFailures < MAX_SUMMARY_FAILURES) {
                try {
                    var result = carryCurrentWork(summarizer.summarize(List.copyOf(original.subList(0, nextBoundary)), policy.summaryMaxTokens()));
                    usage = result.usage();
                    ChatMessage summaryMessage = ChatMessage.assistant(result.summary().render());
                    if (compactor.estimateTokens(List.of(summaryMessage)) > policy.summaryMaxTokens())
                        throw new IllegalArgumentException("摘要超过输出预算");
                    ContextSnapshot candidate = new ContextSnapshot(1, nextBoundary, fingerprint(original.subList(0, nextBoundary)), result.summary());
                    List<ChatMessage> candidatePrefix = prefix(original, candidate);
                    List<ChatMessage> candidateMessages = new ArrayList<>(candidatePrefix);
                    candidateMessages.addAll(compactor.limitToolOutputs(original.subList(nextBoundary, original.size()), toolLimit));
                    int afterCandidate = compactor.estimateTokens(candidateMessages);
                    // 目标比例用于选择边界；有效缩小的摘要即使未达到理想比例也保留，必要时再兜底。
                    if (afterCandidate >= compactor.estimateTokens(working))
                        throw new IllegalArgumentException("摘要没有减小请求，保留原记录");
                    snapshot = candidate; prefix = List.copyOf(candidatePrefix); boundary = nextBoundary;
                    consecutiveFailures = 0;
                    working = candidateMessages; reused = false; reason = "已生成结构化摘要；整理较早的分析与工具结果，保留用户要求及最近工具原文";
                } catch (RuntimeException failure) {
                    // 失败记录只用固定理由，避免错误内容携带秘密；当前任务至少间隔16条消息再尝试。
                    if (failure instanceof ContextSummarizer.Failure consumed) usage = usage.plus(consumed.usage());
                    retryAt = original.size() + 16;
                    consecutiveFailures++;
                    degraded = true; reason = "语义摘要未通过验证或请求失败，降级为本地裁剪；原会话保留";
                }
            } else {
                reason = nextBoundary <= boundary ? "可整理的旧内容不足；用户要求及最近工具结果优先保留" : "摘要暂不可用或处于失败冷却期";
            }
        }
        int projectedBefore = compactor.estimateTokens(working);
        ChatMessage retainedSummary = snapshot == null ? null : prefix.getLast();
        List<ChatMessage> result = compactor.compact(working, budget, toolLimit, policy.semantic(), retainedSummary);
        if (compactor.estimateTokens(result) < projectedBefore) {
            degraded = true;
            if (!reason.contains("裁剪")) reason += "；本次发送内容已做本地裁剪，完整会话保留";
        }
        return new Projection(result, usage, snapshot, new ContextMetrics(before, compactor.estimateTokens(result), budget,
                boundary, reused, degraded, Math.max(0, (System.nanoTime() - started) / 1_000_000), reason));
    }
    private boolean valid(List<ChatMessage> original) {
        if (snapshot == null || !isSummaryBoundaryAllowed(original, snapshot.boundary())) return false;
        // 同一进程中的追加请求直接比较不可变消息，恢复进程时才重新计算指纹。
        if (!prefix.isEmpty() && priorOriginal != null && priorOriginal.size() == snapshot.boundary()
                && original.subList(0, snapshot.boundary()).equals(priorOriginal)) return true;
        return snapshot.prefixFingerprint().equals(fingerprint(original.subList(0, snapshot.boundary())));
    }
    private List<ChatMessage> priorOriginal;
    private List<ChatMessage> projection(List<ChatMessage> original) {
        if (snapshot == null) return original;
        prefix = List.copyOf(prefix(original, snapshot));
        priorOriginal = List.copyOf(original.subList(0, snapshot.boundary()));
        List<ChatMessage> result = new ArrayList<>(prefix);
        result.addAll(original.subList(snapshot.boundary(), original.size()));
        return result;
    }
    private static List<ChatMessage> prefix(List<ChatMessage> original, ContextSnapshot state) {
        List<ChatMessage> result = new ArrayList<>();
        // 固定系统规则与旧轮次全部用户原文；摘要以assistant数据注入，绝不提升为system。
        original.subList(0, state.boundary()).stream().filter(m -> m.role() == ChatMessage.Role.SYSTEM).forEach(result::add);
        original.subList(0, state.boundary()).stream().filter(m -> m.role() == ChatMessage.Role.USER)
                .map(ContextManager::previewOldUser).forEach(result::add);
        result.add(ChatMessage.assistant(state.summary().render()));
        return result;
    }
    /** Old user messages beyond this size are sent as head+tail preview; the full text stays in the transcript. */
    static final int OLD_USER_PREVIEW_CHARS = 4_000;
    static ChatMessage previewOldUser(ChatMessage m) {
        if (m.content().length() <= OLD_USER_PREVIEW_CHARS) return m;
        String note = "\n[harness-preview] older long user message shortened in this request; full text kept in history, use search_history to recover exact wording.\n";
        String body = ContextCompactor.clipMiddle(m.content(), OLD_USER_PREVIEW_CHARS);
        return new ChatMessage(m.role(), body + note, m.toolCalls(), m.toolCallId(), m.toolName(), m.providerMetadata());
    }
    /** Session note: keep the last known current work when a new summary does not report one. */
    private ContextSummarizer.Result carryCurrentWork(ContextSummarizer.Result result) {
        if (snapshot == null || !result.summary().currentWork().isEmpty()
                || snapshot.summary().currentWork().isEmpty()) return result;
        ContextSummary s = result.summary();
        return new ContextSummarizer.Result(new ContextSummary(s.goals(), s.constraints(), s.decisions(), s.completed(),
                s.pending(), s.failures(), s.references(), snapshot.summary().currentWork()), result.usage());
    }
    private int boundary(List<ChatMessage> original, int budget) {
        var blocks = atomicBlocks(original);
        if (blocks.size() < 2) return 0;
        List<Integer> users = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) if (original.get(i).role() == ChatMessage.Role.USER) users.add(i);
        int preferred = users.size() > policy.recentTurns() ? users.get(users.size() - policy.recentTurns()) : 0;
        int protectedBlock = blocks.size() - 1;
        // 最近一次工具调用及全部结果必须留在原文区，摘要不能截断工具组。
        for (int i = blocks.size() - 1; i >= 0; i--) if (!blocks.get(i).getFirst().toolCalls().isEmpty()) {
            protectedBlock = Math.min(protectedBlock, i); break;
        }
        // 不透明回放证据无法被摘要替代；其所在组及后续历史均继续原样保留。
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).stream().anyMatch(ContextManager::hasEvidence)) {
            protectedBlock = Math.min(protectedBlock, i); break;
        }
        long remaining = compactor.estimateTokens(original);
        long removed = 0;
        int offset = 0, selected = 0;
        int target = (int) (budget * policy.targetRatio());
        for (int i = 0; i < protectedBlock; i++) {
            var block = blocks.get(i);
            offset += block.size();
            var data = block.stream().filter(m -> m.role() != ChatMessage.Role.SYSTEM && m.role() != ChatMessage.Role.USER).toList();
            int tokens = compactor.estimateTokens(data);
            removed += tokens;
            remaining -= tokens;
            // 用户原文和系统规则始终保留；只有有实质压缩空间才调用摘要模型。
            if (removed <= policy.summaryMaxTokens()) continue;
            selected = offset;
            // 轮数是偏好，实际 token 预算才是上限；两三轮的长任务也可整理已完成的工具组。
            if (offset >= preferred && remaining + policy.summaryMaxTokens() <= target) break;
        }
        return selected;
    }
    private static boolean hasEvidence(ChatMessage m) {
        return !m.providerMetadata().isEmpty() || m.toolCalls().stream().anyMatch(c -> !c.providerMetadata().isEmpty());
    }
    /** Tools whose results can be re-read from the archive or re-run cheaply. */
    static final java.util.Set<String> CLEARABLE_TOOLS = java.util.Set.of(
            "read_file", "search_files", "list_files", "run_command", "repository_map", "changes", "web_fetch");
    /** Most recent clearable results always stay verbatim. */
    static final int KEEP_RECENT_RESULTS = 8;
    /** Clearing advances in fixed batches so the request prefix stays byte-stable between batches (prompt cache). */
    static final int CLEAR_MIN_CHARS = 400;
    static final String CLEARED_PREFIX = "[harness-cleared] older tool result removed from context; original kept in archive.";

    private static boolean clearable(ChatMessage m) {
        return m.role() == ChatMessage.Role.TOOL && m.providerMetadata().isEmpty() && m.toolCallId() != null
                && m.toolName() != null && CLEARABLE_TOOLS.contains(m.toolName());
    }
    /** Marks every clearable result except the most recent ones; short results are never marked, so they cost no cache. */
    void advanceCleared(List<ChatMessage> messages) {
        List<ChatMessage> candidates = messages.stream().filter(ContextManager::clearable).toList();
        for (int k = 0; k < candidates.size() - KEEP_RECENT_RESULTS; k++) {
            ChatMessage m = candidates.get(k);
            if (m.content().length() >= CLEAR_MIN_CHARS && !m.content().startsWith(CLEARED_PREFIX)) cleared.add(m.toolCallId());
        }
    }
    java.util.Set<String> clearedIds() { return java.util.Set.copyOf(cleared); }
    static List<ChatMessage> microCompact(List<ChatMessage> messages, java.util.Set<String> clearedIds) {
        if (clearedIds.isEmpty()) return messages;
        List<ChatMessage> result = new ArrayList<>(messages);
        boolean changed = false;
        for (int i = 0; i < result.size(); i++) {
            ChatMessage m = result.get(i);
            if (!clearable(m) || !clearedIds.contains(m.toolCallId()) || m.content().startsWith(CLEARED_PREFIX)) continue;
            result.set(i, new ChatMessage(m.role(), clearedText(m), m.toolCalls(), m.toolCallId(), m.toolName(), m.providerMetadata()));
            changed = true;
        }
        return changed ? List.copyOf(result) : messages;
    }
    private static String clearedText(ChatMessage m) {
        StringBuilder text = new StringBuilder(CLEARED_PREFIX);
        // Keep archive pointers so the model can page the original back with read_tool_output.
        m.content().lines().filter(line -> line.contains("read_tool_output") || line.contains("archive"))
                .limit(3).forEach(line -> text.append('\n').append(line.length() > 300 ? line.substring(0, 300) : line));
        text.append("\nUse read_tool_output or search_history if this result is needed again; do not re-run the tool blindly.");
        return text.toString();
    }
    public static List<List<ChatMessage>> atomicBlocks(List<ChatMessage> messages) {
        ToolHistory.validate(messages, false);
        List<List<ChatMessage>> blocks = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            int start = i;
            if (!messages.get(i).toolCalls().isEmpty()) while (i + 1 < messages.size() && messages.get(i + 1).role() == ChatMessage.Role.TOOL) i++;
            blocks.add(List.copyOf(messages.subList(start, i + 1)));
        }
        return List.copyOf(blocks);
    }
    /** 恢复时重新核对摘要边界，不能截断工具组或吞掉厂商回放证据。 */
    public static boolean isSummaryBoundaryAllowed(List<ChatMessage> original, int boundary) {
        if (boundary < 1 || boundary > original.size()) return false;
        if (boundary < original.size() && original.get(boundary).role() == ChatMessage.Role.TOOL) return false;
        for (int i = 0; i < boundary; i++) if (hasEvidence(original.get(i))) return false;
        try { ToolHistory.validate(original.subList(0, boundary), false); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static String fingerprint(List<ChatMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ChatMessage m : messages) {
                // 系统指引按当前任务重建，不属于历史摘要的事实内容。
                field(digest, m.role().name()); field(digest, m.role() == ChatMessage.Role.SYSTEM ? "" : m.content()); field(digest, m.toolCallId()); field(digest, m.toolName());
                for (var call : m.toolCalls()) {
                    field(digest, call.id()); field(digest, call.name()); field(digest, call.argumentsJson());
                    call.providerMetadata().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                            .forEach(e -> { field(digest, e.getKey()); field(digest, e.getValue()); });
                }
                m.providerMetadata().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(e -> { field(digest, e.getKey()); field(digest, e.getValue()); });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void field(MessageDigest digest, String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    public record Projection(List<ChatMessage> messages, TokenUsage summaryUsage, ContextSnapshot snapshot, ContextMetrics metrics) {}
}
