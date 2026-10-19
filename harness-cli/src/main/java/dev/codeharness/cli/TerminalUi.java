package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.cli.approval.CommandReview;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;

/** 任务和回复正常滚动，交互用量集中到底部；输入仍由原读取器管理。 */
final class TerminalUi implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean color;
    private boolean answerStarted;
    private boolean progressStarted;
    private boolean showProgress = true;
    private int taskNumber;
    private int step;
    private String provider = "";
    private String model = "";
    private String host = "";
    private BottomStatusBar bottom;
    private UsageDashboard.Snapshot currentTask = UsageDashboard.Snapshot.empty("current-task");
    private UsageDashboard.Snapshot currentProcess = UsageDashboard.Snapshot.empty("current-process");
    private CommandReview.Stats currentReview = new CommandReview.Stats(0, 0, 0, 0, dev.codeharness.core.model.TokenUsage.ZERO, 0);
    private String activity = "就绪";
    private int mainRequests;
    private int tools;
    private long taskStarted;
    private double elapsedSeconds;

    TerminalUi(PrintWriter out, PrintWriter err, boolean color) {
        this.out = out;
        this.err = err;
        this.color = color;
    }

    static boolean supportsColor(boolean terminal) {
        if (!terminal || System.getenv("NO_COLOR") != null || "dumb".equals(System.getenv("TERM"))) return false;
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                || System.getenv("WT_SESSION") != null || System.getenv("ANSICON") != null
                || "ON".equals(System.getenv("ConEmuANSI"))
                || System.getenv("TERM") != null && System.getenv("TERM").startsWith("xterm");
    }

    void connection(String provider, String model, String host) {
        this.provider = safe(provider);
        this.model = safe(model);
        this.host = safe(host);
    }

    void welcome(Path workspace, String provider, String model, String session) {
        connection(provider, model, host);
        out.println();
        out.println("  " + style("1;36", "ylc") + "  " + style("90", "YLCoder-harness · " + brief(safe(workspace.toString()), 48)));
        out.println("  " + style("36", "/project") + "  " + style("36", "/status") + "  "
                + style("36", "/resume") + "  " + style("36", "/help") + "  " + style("36", "/exit"));
        out.println();
        out.flush();
    }

    void openBottom(boolean allowed) { bottom = BottomStatusBar.open(err, allowed); }

    synchronized void beforeOutput() {
        if (bottom != null && bottom.active()) {
            out.flush();
            err.flush();
            bottom.beforeOutput();
        }
    }

    synchronized void live(UsageDashboard.Snapshot task, UsageDashboard.Snapshot process,
                           CommandReview.Stats review, int mainRequests, int tools, String activity) {
        currentTask = task;
        currentProcess = process;
        currentReview = review;
        this.mainRequests = Math.max(mainRequests, task.requests() - review.requests());
        this.tools = tools;
        this.activity = activity;
        refreshBottom();
    }

    synchronized void phase(String value) { activity = value; refreshBottom(); }

    synchronized void retryStream() {
        beforeOutput();
        err.println("  ↻ 流式暂不可用，正在自动改用非流式重试（1/1）…");
        err.flush();
        mainRequests++;
        phase("正在重试");
    }

    private void refreshBottom() {
        if (bottom != null && bottom.active()) {
            out.flush();
            err.flush();
            bottom.update(bottomLines());
        }
    }

    synchronized void prompt() { prompt(true); }

    synchronized void prompt(boolean showPrompt) {
        refreshBottom();
        if (bottom == null || !bottom.active()) compact(out, "状态");
        if (showPrompt) out.print(style("1;36", "› "));
        out.flush();
    }

    /** 管道输入没有终端回显，需要主动结束提示符所在行。 */
    void inputFinished(boolean terminal) {
        beforeOutput();
        if (!terminal) { out.println(); out.flush(); }
    }

    void help(boolean saved, String session) {
        beforeOutput();
        section(out, "帮助");
        out.println("  /project  查看本地项目概览，不调用模型");
        out.println("  /status   查看连接、工作区、进程累计用量和完整会话 ID");
        out.println("  /resume   列出历史会话；/resume 序号或会话ID 切换后继续输入任务");
        out.println("  /history  查看最近历史；/history 页码 翻页，/history all 查看全部");
        out.println("  Tab       补全斜杠命令及参数；支持方向键编辑输入");
        out.println("  /stream   查看流式状态；/stream off 关闭，/stream on 开启");
        out.println("  /help     显示帮助");
        out.println("  /exit     退出");
        out.println("  Ctrl+C    中断当前任务并退出会话，保留已保存的记录");
        out.println();
        out.println("  示例：分析当前项目，先查看目录和 README，再说明启动方式。");
        out.println("  请输入编程任务。任务会自动保存。输入 /exit 或按 Ctrl+C 退出。");
        out.println(saved ? "  继续会话：ylc resume " + safe(session) : "  尚未保存任何任务。请先提交任务，再继续此会话。");
        out.println();
        out.flush();
    }

    void sessions(java.util.List<SessionStore.Summary> sessions, String currentId) {
        beforeOutput();
        section(out, "历史会话");
        if (sessions.isEmpty()) {
            out.println("  当前工作区没有可恢复的会话。先提交一项任务，完成或中断后会自动保存检查点。");
        } else {
            var format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());
            for (int index = 0; index < sessions.size(); index++) {
                var session = sessions.get(index);
                String status = switch (session.status()) {
                    case "COMPLETED" -> "已完成";
                    case "FAILED" -> "未完成";
                    case "RUNNING" -> "运行中或已中断";
                    case "CANCELLED" -> "已取消";
                    case "MAX_ITERATIONS" -> "达到调用上限";
                    default -> safe(session.status());
                };
                out.println("  " + (index + 1) + ". " + format.format(java.time.Instant.parse(session.updatedAt()))
                        + " · " + status + " · " + brief(safe(session.model()), 28));
                out.println("     " + brief(safe(session.task()), 68));
                out.println("     " + safe(session.id()) + (session.id().equals(currentId) ? "（当前）" : ""));
                out.println();
            }
            out.println("  输入 /resume 1 恢复第 1 项，或 /resume 完整会话ID；输入其他内容可继续当前会话。");
        }
        out.println("  仅查看当前工作区最近 20 个检查点；无效或损坏的检查点不会列出。此操作不调用模型。");
        out.println();
        out.flush();
    }

    synchronized void resumed(String id, java.util.List<dev.codeharness.core.model.ChatMessage> transcript,
                              UsageDashboard.Snapshot process) {
        beforeOutput();
        answerStarted = progressStarted = false;
        taskStarted = 0;
        elapsedSeconds = 0;
        out.println();
        out.println("  已恢复会话：" + safe(id));
        out.println("  已进入该会话，下面显示历史对话；尚未请求模型。");
        out.println();
        ConversationHistory.render(transcript, null, out::println);
        out.flush();
        live(UsageDashboard.Snapshot.empty("current-task"), process,
                new CommandReview.Stats(0, 0, 0, 0, TokenUsage.ZERO, 0), 0, 0, "就绪");
    }

    void history(java.util.List<dev.codeharness.core.model.ChatMessage> transcript, String selection) {
        beforeOutput();
        out.println();
        ConversationHistory.render(transcript, selection, out::println);
        out.flush();
    }

    void status(Path workspace, String session, boolean saved, UsageDashboard.Snapshot process) {
        beforeOutput();
        section(out, "连接与工作区");
        identity(out);
        out.println();
        field(out, "工作区", safe(workspace.toString()));
        field(out, "会话", safe(session) + (saved ? "（已保存）" : "（尚未提交任务）"));
        if (saved) out.println("  继续会话：ylc resume " + safe(session));
        section(out, "进程累计");
        usage(out, process);
        field(out, "模型请求", process.requests() + " 次（含审核）");
        out.println("  " + style("90", "仅统计本次启动；恢复会话不包含历史计费用量。"));
        if (currentTask.requests() > 0) {
            section(out, "最近任务");
            usage(out, currentTask);
            field(out, "命令审核", currentReview.requests() + " 次请求，自动通过 " + currentReview.approved()
                    + " 次，复用 " + currentReview.cacheHits() + " 次，转人工 " + currentReview.manual() + " 次");
            out.println("  审核用量已计入本轮已知用量；审核结果复用与模型 token 缓存不同。");
        }
        out.println();
        out.flush();
    }

    void beginTask(boolean showProgress) {
        answerStarted = progressStarted = false;
        this.showProgress = showProgress;
        taskNumber++;
        step = 0;
        taskStarted = System.nanoTime();
        elapsedSeconds = 0;
        activity = "正在思考";
    }

    private void progress() {
        beforeOutput();
        if (!progressStarted) {
            err.println();
        }
        progressStarted = true;
        answerStarted = false;
    }

    synchronized void thinking(int iteration) {
        if (!showProgress) return;
        progress();
        step++;
        err.printf("  %s %s%n", style("90", "●"),
                style("90", iteration == 1 ? "正在思考…" : "整理结果…（第 " + iteration + " 次请求）"));
        err.flush();
        refreshBottom();
    }

    synchronized void tool(ToolCall call) {
        if (!showProgress) return;
        progress();
        step++;
        String label = switch (call.name()) {
            case "list_files" -> "浏览项目";
            case "search_files" -> "搜索文件";
            case "read_file" -> "读取文件";
            case "write_file" -> "写入文件";
            case "edit_file" -> "编辑文件";
            case "changes" -> "检查变更";
            case "run_command" -> "执行命令";
            default -> "调用工具 " + safe(call.name());
        };
        String path = "";
        if (switch (call.name()) { case "list_files", "search_files", "read_file", "write_file", "edit_file" -> true; default -> false; }) {
            try {
                var node = JSON.readTree(call.argumentsJson()).get("path");
                if (node != null && node.isTextual()) path = safe(node.textValue());
            } catch (Exception ignored) {
                // 参数由工具验证；展示层不回显可能包含秘密的原始参数或异常。
            }
        }
        err.printf("  %s %s%s%n", style("90", "●"), style("36", label),
                path.isEmpty() ? "" : "  " + style("90", brief(path, 50)));
        err.flush();
        refreshBottom();
    }

    synchronized void review(String notice) {
        if (!showProgress) return;
        progress();
        err.println("    " + style("90", safe(notice)));
        err.flush();
        refreshBottom();
    }

    synchronized void toolError(String name) {
        if (!showProgress) return;
        beforeOutput();
        err.println("    " + style("33", "工具未完成：" + safe(name) + "；正在把错误交给模型处理。"));
        err.flush();
        refreshBottom();
    }

    synchronized void answer() {
        beforeOutput();
        if (answerStarted) return;
        if (progressStarted) {
            err.printf("  %s %s%n", style("32", "✓"), style("32", activity + " · " + step + " 次" + (tools == 0 ? "" : "工具") + " · "
                    + String.format(Locale.ROOT, "%.1f 秒", (System.nanoTime() - taskStarted) / 1_000_000_000.0)));
            err.println();
            err.flush();
        }
        out.println();
        out.flush();
        answerStarted = true;
        progressStarted = false;
    }

    void summary(String session, AgentResult result, int toolCount, boolean interactive,
                 UsageDashboard.Snapshot task, UsageDashboard.Snapshot process, CommandReview.Stats review) {
        String status = switch (result.status()) {
            case COMPLETED -> toolCount == 0 ? "已回复" : "完成";
            case MAX_ITERATIONS -> "达到调用上限";
            case CANCELLED -> "已取消";
            case FAILED -> "未完成";
        };
        elapsedSeconds = result.elapsed().toMillis() / 1000.0;
        taskStarted = 0;
        live(task, process, review, Math.max(0, task.requests() - review.requests()), toolCount, status);
        if (interactive) {
            beforeOutput();
            out.println();
            String input = compactAmount(task.total().inputTokens(), task.inputReportedRequests(), task.requests());
            String output = compactAmount(task.total().outputTokens(), task.outputReportedRequests(), task.requests());
            String rate = cacheRate(task, true);
            out.println("  " + style("90", status + " · ↑" + input + " ↓" + output + " · 缓存 " + rate + " · "
                    + String.format(Locale.ROOT, "%.1f 秒", elapsedSeconds)));
            return;
        }
        compact(err, status);
        if (!interactive) {
            err.println("  继续：ylc resume " + safe(session));
        }
        err.println();
        err.flush();
    }

    /** 四行关键状态代替重复大面板；累计详情可在 /status 查看。 */
    private java.util.List<String> bottomLines() {
        double seconds = taskStarted == 0 ? elapsedSeconds : (System.nanoTime() - taskStarted) / 1_000_000_000.0;
        String cached = compactAmount(currentTask.total().cachedInputTokens(), currentTask.cacheReportedRequests(), currentTask.requests());
        String rate = cacheRate(currentTask, true);
        String review = currentReview.cacheHits() == 0 && currentReview.manual() == 0 ? ""
                : " · 审核复用" + currentReview.cacheHits() + "/人工" + currentReview.manual();
        return java.util.List.of(
                "  模型 " + brief(model, 28) + " · 厂商 " + brief(host.isBlank() ? provider : host, 24),
                "  本轮  输入 token " + compactAmount(currentTask.total().inputTokens(), currentTask.inputReportedRequests(), currentTask.requests())
                        + " · 输出 token " + compactAmount(currentTask.total().outputTokens(), currentTask.outputReportedRequests(), currentTask.requests()),
                "  缓存 token " + cached + " · 缓存命中率 " + rate,
                "  " + activity + " · " + mainRequests + "次主模型/" + currentReview.requests() + "次审核 · "
                        + (tools == 0 ? "未调用工具" : tools + "次工具") + String.format(Locale.ROOT, " · %.1f 秒", seconds) + review);
    }

    private static String compactAmount(long value, int reported, int requests) {
        if (requests == 0) return "—";
        if (reported == 0) return "— 未报告";
        return String.format(Locale.ROOT, "%,d", value) + (reported < requests ? "（部分）" : "");
    }

    private static String brief(String value, int columns) {
        var result = new StringBuilder();
        int used = 0;
        for (int code : value.codePoints().toArray()) {
            int width = code < 128 ? 1 : 2;
            if (used + width > columns - 1) return result + "…";
            used += width;
            result.appendCodePoint(code);
        }
        return result.toString();
    }

    private void compact(PrintWriter writer, String title) {
        writer.println();
        writer.println("  " + style("36", title));
        bottomLines().forEach(writer::println);
        writer.println();
        writer.flush();
    }

    @Override public synchronized void close() { if (bottom != null) bottom.close(); }

    private void identity(PrintWriter writer) {
        field(writer, "模型", model.isBlank() ? "—" : model);
        field(writer, "厂商", host.isBlank() ? provider : host);
        field(writer, "接口协议", provider);
    }

    private void usage(PrintWriter writer, UsageDashboard.Snapshot snapshot) {
        field(writer, "输入 token", amount(snapshot.total().inputTokens(), snapshot.inputReportedRequests(), snapshot.requests()));
        field(writer, "输出 token", amount(snapshot.total().outputTokens(), snapshot.outputReportedRequests(), snapshot.requests()));
        field(writer, "缓存 token", amount(snapshot.total().cachedInputTokens(), snapshot.cacheReportedRequests(), snapshot.requests()));
        String rate = cacheRate(snapshot, false);
        field(writer, "缓存命中率", rate);
        if (snapshot.requests() > 0 && (snapshot.inputReportedRequests() < snapshot.requests()
                || snapshot.outputReportedRequests() < snapshot.requests() || snapshot.cacheReportedRequests() < snapshot.requests()))
            writer.println("  " + style("90", "未报告的部分不计入已知用量；不代表免费或 0% 命中。"));
    }

    private static String cacheRate(UsageDashboard.Snapshot snapshot, boolean compact) {
        String rate;
        if (snapshot.requests() == 0) rate = "—（尚无请求）";
        else if (snapshot.cacheHitRatePercent() != null) rate = percent(snapshot.cacheHitRatePercent()) + (compact ? "" : "（缓存输入 / 输入）");
        else if (snapshot.reportedCacheHitRatePercent() != null) rate = percent(snapshot.reportedCacheHitRatePercent()) + (compact ? "（部分）" : "（仅已报告部分）");
        else if (snapshot.cacheReportedRequests() == 0) rate = compact ? "— 未报告" : "—（缓存用量未报告）";
        else rate = compact ? "— 未报告" : "—（缺少有效的输入与缓存用量）";
        return rate;
    }

    private static String amount(long amount, int reported, int requests) {
        if (requests == 0) return "—（尚无请求）";
        if (reported == 0) return "—（用量未报告）";
        return String.format(Locale.ROOT, "%,d", amount) + (reported < requests ? "（部分报告 " + reported + "/" + requests + "）" : "");
    }

    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value); }
    private void field(PrintWriter writer, String label, String value) {
        // 标签为固定中文和 ASCII；按显示列补空格，不使用受 CJK 字宽影响的右边框。
        int width = label.codePoints().map(code -> code < 128 ? 1 : 2).sum();
        writer.println("  " + style("90", label) + " ".repeat(Math.max(2, 14 - width)) + value);
    }
    private void section(PrintWriter writer, String title) {
        writer.println();
        writer.println("  " + style("1;36", "── " + title + " ──"));
        writer.println();
    }
    private String style(String code, String text) { return color ? "\033[" + code + "m" + text + "\033[0m" : text; }

    /** 配置和工具参数不能注入终端控制序列或换行。 */
    private static String safe(String text) {
        if (text == null) return "";
        var cleaned = new StringBuilder();
        text.codePoints().limit(180).forEach(code -> {
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT || code == 0x2028 || code == 0x2029) cleaned.append(' ');
            else cleaned.appendCodePoint(code);
        });
        if (text.codePointCount(0, text.length()) > 180) cleaned.append('…');
        return cleaned.toString();
    }
}
