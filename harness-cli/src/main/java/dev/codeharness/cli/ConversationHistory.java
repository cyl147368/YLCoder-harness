package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

/** 历史按原顺序呈现；系统提示、工具参数和工具原文不进入默认对话视图。 */
final class ConversationHistory {
    static final int PAGE_SIZE = 12;
    private static final int MESSAGE_LIMIT = 8_000;
    private static final int DISPLAY_LIMIT = 120_000;
    private ConversationHistory() { }

    static void render(List<ChatMessage> transcript, String selection, Consumer<String> output) {
        var messages = transcript.stream().filter(message -> message.role() != ChatMessage.Role.SYSTEM).toList();
        int pages = Math.max(1, (messages.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        boolean all = "all".equals(selection);
        int page = pages;
        if (selection != null && !selection.isBlank() && !all) {
            try { page = Integer.parseInt(selection); }
            catch (NumberFormatException invalid) { page = 0; }
            if (page < 1 || page > pages) {
                output.accept("  页码无效。可用 /history 1 到 /history " + pages + "，或 /history all。");
                return;
            }
        }
        output.accept("  ── 历史对话 · " + (all ? "全部" : "第 " + page + "/" + pages + " 页") + " ──");
        output.accept("");
        if (messages.isEmpty()) output.accept("  尚无对话记录。");
        int first = all ? 0 : (page - 1) * PAGE_SIZE;
        int end = all ? messages.size() : Math.min(messages.size(), first + PAGE_SIZE);
        int remaining = DISPLAY_LIMIT;
        for (int index = first; index < end; index++) {
            if (remaining <= 0) {
                output.accept("  … 本次显示已达上限，请使用 /history 页码 分页查看。");
                break;
            }
            ChatMessage message = messages.get(index);
            String label = switch (message.role()) {
                case USER -> "你";
                case ASSISTANT -> "助手";
                case TOOL -> "工具结果";
                default -> "";
            };
            output.accept("  " + label);
            if (message.role() == ChatMessage.Role.TOOL) {
                output.accept("    " + text(message.toolName() == null ? "工具" : message.toolName(), 120)
                        + " · 结果已保存在上下文中");
            } else {
                int limit = Math.min(MESSAGE_LIMIT, remaining);
                String content = text(message.content(), limit);
                remaining -= Math.min(limit, message.content().codePointCount(0, message.content().length()));
                for (String line : content.split("\\R", -1)) output.accept("    " + line);
                if (!message.toolCalls().isEmpty()) {
                    output.accept("    工具调用：" + text(String.join("、", message.toolCalls().stream().map(call -> call.name()).toList()), 300));
                }
            }
            output.accept("");
            remaining -= 512; // 工具摘要与标签也占显示预算，避免大量空内容记录刷屏。
        }
        output.accept("  /history 查看最近一页 · /history 页码 翻页 · /history all 查看全部（单条长消息会截断）");
        output.accept("  以上为本地历史，不调用模型。直接输入任务即可在此会话继续。");
        output.accept("");
    }

    static String text(String value, int limit) {
        var cleaned = new StringBuilder();
        int count = 0;
        var codes = value.codePoints().iterator();
        while (codes.hasNext() && count++ < limit) {
            int code = codes.nextInt();
            if (code == '\t') cleaned.append("    ");
            else if (code == '\n') cleaned.append('\n');
            else if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT
                    || code == 0x2028 || code == 0x2029) cleaned.append(' ');
            else cleaned.appendCodePoint(code);
        }
        if (codes.hasNext()) cleaned.append("\n… 此条历史较长，显示已截断");
        return cleaned.toString();
    }
}
