package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import dev.codeharness.core.model.ToolDefinition;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性限制消息上下文，不改变指令或工具调用 JSON。 */
public final class ContextCompactor {
    private static final String SUMMARY_PREFIX = "[harness-context-v1] ";
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(Pattern.quote(SUMMARY_PREFIX)
            + "messages=([0-9]{1,10});users=([0-9]{1,10});assistants=([0-9]{1,10});results=([0-9]{1,10});calls=([0-9]{1,10})");
    private static final long MAX_SUMMARY_COUNT = 9_999_999_999L;
    private final InputTokenCounter tokenCounter;

    public ContextCompactor() { this(InputTokenCounter.UTF16); }

    public ContextCompactor(InputTokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    public List<ChatMessage> compact(List<ChatMessage> input, int tokenBudget, int maxToolOutputChars) {
        if (tokenBudget < 1) throw new IllegalArgumentException("tokenBudget 必须为正数");
        if (maxToolOutputChars < 1) throw new IllegalArgumentException("maxToolOutputChars 必须为正数");
        Summary summary = new Summary();
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessage message : input) {
            if (!summary.consume(message)) messages.add(compactToolOutput(message, maxToolOutputChars));
        }
        List<Block> blocks = blocks(messages);
        protectRequiredBlocks(blocks);
        List<ChatMessage> result = assemble(blocks, summary);
        for (Block block : blocks) {
            if (estimateTokens(result) <= tokenBudget) break;
            if (block.required) continue;
            block.removed = true;
            summary.add(block.messages);
            result = assemble(blocks, summary);
        }
        // 自动生成的数量摘要是可选的；厂商回放证据不可拆分。
        if (estimateTokens(result) > tokenBudget && summary.messages > 0) result = assemble(blocks, null);
        if (estimateTokens(result) > tokenBudget) shrinkContents(result, tokenBudget);
        int estimatedTokens = estimateTokens(result);
        if (estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("压缩后上下文约需要 " + estimatedTokens
                    + " token，超过上下文预算 " + tokenBudget
                    + "。请增加上下文预算，或缩短指令、工具参数。");
        }
        return List.copyOf(result);
    }

    /** 所选本地计数策略，不代表厂商计费测量。 */
    public int estimateTokens(List<ChatMessage> messages) {
        int count = tokenCounter.messageTokens(messages);
        if (count < 0) throw new IllegalArgumentException("token 计数器返回了负数");
        return count;
    }

    public int estimateToolTokens(List<ToolDefinition> tools) {
        int count = tokenCounter.toolTokens(tools);
        if (count < 0) throw new IllegalArgumentException("token 计数器返回了负数");
        return count;
    }

    /** 工具调用及其全部结果构成不可拆分块；畸形历史在本地失败。 */
    private List<Block> blocks(List<ChatMessage> messages) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatMessage.Role.TOOL) throw invalidPairing();
            List<ChatMessage> group = new ArrayList<>();
            group.add(message);
            if (!message.toolCalls().isEmpty()) {
                Set<String> pending = new HashSet<>();
                for (ToolCall call : message.toolCalls()) if (!pending.add(call.id())) throw invalidPairing();
                while (i + 1 < messages.size() && messages.get(i + 1).role() == ChatMessage.Role.TOOL) {
                    ChatMessage result = messages.get(++i);
                    if (!pending.remove(result.toolCallId())) throw invalidPairing();
                    group.add(result);
                }
                if (!pending.isEmpty()) throw invalidPairing();
            }
            blocks.add(new Block(group));
        }
        return blocks;
    }

    private static IllegalArgumentException invalidPairing() {
        return new IllegalArgumentException("会话中有未匹配或重复的工具调用及结果；压缩前请修复会话。");
    }

    private void protectRequiredBlocks(List<Block> blocks) {
        Block originalTask = null;
        Block latestUser = null;
        Block latestAssistant = null;
        Block latestToolCycle = null;
        for (Block block : blocks) {
            ChatMessage first = block.messages.getFirst();
            if (first.role() == ChatMessage.Role.SYSTEM) block.required = true;
            if (first.role() == ChatMessage.Role.USER) {
                if (originalTask == null) originalTask = block;
                latestUser = block;
            }
            if (first.role() == ChatMessage.Role.ASSISTANT) latestAssistant = block;
            if (!first.toolCalls().isEmpty()) latestToolCycle = block;
        }
        for (Block block : blocks) {
            if (block == originalTask || block == latestUser || block == latestAssistant || block == latestToolCycle) {
                block.required = true;
            }
        }
    }

    private List<ChatMessage> assemble(List<Block> blocks, Summary summary) {
        List<ChatMessage> result = new ArrayList<>();
        int summaryIndex = -1;
        for (Block block : blocks) {
            if (block.removed) continue;
            result.addAll(block.messages);
            if (summaryIndex < 0 && block.messages.getFirst().role() == ChatMessage.Role.USER) summaryIndex = result.size();
        }
        if (summary != null && summary.messages > 0) {
            if (summaryIndex < 0) {
                summaryIndex = 0;
                while (summaryIndex < result.size() && result.get(summaryIndex).role() == ChatMessage.Role.SYSTEM) summaryIndex++;
            }
            result.add(summaryIndex, ChatMessage.assistant(summary.render()));
        }
        return result;
    }

    private ChatMessage compactToolOutput(ChatMessage message, int limit) {
        if (hasReplayEvidence(message) || message.role() != ChatMessage.Role.TOOL || message.content().length() <= limit) return message;
        return withContent(message, clipMiddle(message.content(), limit));
    }

    private void shrinkContents(List<ChatMessage> messages, int tokenBudget) {
        for (int i = 0; i < messages.size() && estimateTokens(messages) > tokenBudget; i++) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatMessage.Role.SYSTEM || message.role() == ChatMessage.Role.USER) continue;
            if (hasReplayEvidence(message)) continue;
            if (message.content().isEmpty()) continue;
            ChatMessage fitting = withContent(message, "");
            messages.set(i, fitting);
            if (estimateTokens(messages) > tokenBudget) continue;
            // 使用计数策略单位搜索可保留内容，不假定固定字符与 token 比例。
            // BPE 计数不必单调；只保留经过显式计数确认能放入预算的候选内容。
            int low = 0;
            int high = message.content().length();
            while (low < high) {
                int keep = low + (high - low + 1) / 2;
                ChatMessage candidate = withContent(message, clipMiddle(message.content(), keep));
                messages.set(i, candidate);
                if (estimateTokens(messages) <= tokenBudget) { fitting = candidate; low = keep; }
                else high = keep - 1;
            }
            messages.set(i, fitting);
        }
    }

    private static ChatMessage withContent(ChatMessage message, String content) {
        return new ChatMessage(message.role(), content, message.toolCalls(), message.toolCallId(), message.toolName(), message.providerMetadata());
    }

    private static boolean hasReplayEvidence(ChatMessage message) {
        return !message.providerMetadata().isEmpty()
                || message.toolCalls().stream().anyMatch(call -> !call.providerMetadata().isEmpty());
    }

    static String clipMiddle(String value, int limit) {
        if (value.length() <= limit) return value;
        if (limit <= 24) return value.substring(0, safePrefix(value, Math.max(0, limit)));
        String marker = "\n… [内容已压缩] …\n";
        int half = Math.max(0, (limit - marker.length()) / 2);
        int end = value.length() - half;
        if (end > 0 && end < value.length() && Character.isLowSurrogate(value.charAt(end))
                && Character.isHighSurrogate(value.charAt(end - 1))) end++;
        return value.substring(0, safePrefix(value, half)) + marker + value.substring(end);
    }

    private static int safePrefix(String value, int end) {
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) return end - 1;
        return end;
    }

    private static final class Block {
        private final List<ChatMessage> messages;
        private boolean required;
        private boolean removed;
        private Block(List<ChatMessage> messages) { this.messages = messages; }
    }

    /** 仅统计数量：不可信文本、路径、参数和工具名绝不转为摘要指令。 */
    private static final class Summary {
        private long messages;
        private long users;
        private long assistants;
        private long results;
        private long calls;

        private boolean consume(ChatMessage message) {
            if (message.role() != ChatMessage.Role.ASSISTANT || !message.toolCalls().isEmpty() || !message.providerMetadata().isEmpty()) return false;
            Matcher matcher = SUMMARY_PATTERN.matcher(message.content());
            if (!matcher.matches()) return false;
            messages = plus(messages, Long.parseLong(matcher.group(1)));
            users = plus(users, Long.parseLong(matcher.group(2)));
            assistants = plus(assistants, Long.parseLong(matcher.group(3)));
            results = plus(results, Long.parseLong(matcher.group(4)));
            calls = plus(calls, Long.parseLong(matcher.group(5)));
            return true;
        }

        private void add(List<ChatMessage> omitted) {
            for (ChatMessage message : omitted) {
                messages = plus(messages, 1);
                if (message.role() == ChatMessage.Role.USER) users = plus(users, 1);
                if (message.role() == ChatMessage.Role.ASSISTANT) assistants = plus(assistants, 1);
                if (message.role() == ChatMessage.Role.TOOL) results = plus(results, 1);
                calls = plus(calls, message.toolCalls().size());
            }
        }

        private String render() {
            return SUMMARY_PREFIX + "messages=" + messages + ";users=" + users + ";assistants=" + assistants
                    + ";results=" + results + ";calls=" + calls;
        }

        private static long plus(long first, long second) { return Math.min(MAX_SUMMARY_COUNT, first + second); }
    }
}
