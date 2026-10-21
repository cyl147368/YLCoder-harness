package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.agent.ToolHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 只在用户提问之前切分，保留完整的工具调用与结果，不重放工具。 */
final class ConversationRewind {
    private ConversationRewind() { }
    record Point(int number, int offset, String question) { }

    static List<Point> points(List<ChatMessage> history) {
        var result = new ArrayList<Point>();
        for (int index = 0; index < history.size(); index++) {
            var message = history.get(index);
            if (message.role() == ChatMessage.Role.USER)
                result.add(new Point(result.size() + 1, index, message.content()));
        }
        return List.copyOf(result);
    }

    static List<ChatMessage> before(List<ChatMessage> history, int number) {
        var points = points(history);
        if (number < 1 || number > points.size()) throw new IllegalArgumentException("回退序号无效");
        var result = List.copyOf(history.subList(0, points.get(number - 1).offset()));
        ToolHistory.validate(result, false);
        return result;
    }

    static void render(List<Point> points, boolean all, Consumer<String> output) {
        output.accept("");
        output.accept("  ── 回退对话 · 选择重新开始的位置 ──");
        output.accept("  回到所选提问之前；该提问及之后的对话不进入新分支。文件和已执行命令不会撤销。");
        output.accept("  原会话保留，可用 /resume 恢复；此操作不调用模型、不重放工具。");
        output.accept("");
        for (var point : points.subList(all ? 0 : Math.max(0, points.size() - 20), points.size()))
            output.accept("  " + point.number() + ". " + ConversationHistory.text(point.question(), 100).replace('\n', ' '));
        if (points.isEmpty()) output.accept("  当前没有可回退的提问。");
        else output.accept("  输入序号后回车选择；直接回车或 Esc 取消。也可用 /rewind 序号；/rewind all 列出全部。");
        if (!all && points.size() > 20) output.accept("  当前显示最近 20 个回退点，编号对应当前保留的上下文。");
        output.accept("");
    }
}
