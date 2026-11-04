package dev.codeharness.cli;

import dev.codeharness.cli.approval.CommandReview;
import dev.codeharness.core.model.TokenUsage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class TerminalPerformanceTest {
    @Test void thousandTextEventsRefreshAtMostFourTimesPerSecondWithoutRepeatedClearing() {
        var sink = new StringWriter();
        var writer = new PrintWriter(sink);
        var screen = new CountingSurface();
        var clock = new AtomicLong(1_000_000_000L);
        try (var ui = new TerminalUi(writer, writer, false, new BottomStatusBar(writer, screen), clock::get)) {
            ui.connection("fixture", "fixture-model", "localhost");
            ui.beginTask(false);
            ui.answer();
            ui.phase("正在输出");
            sink.getBuffer().setLength(0);
            screen.queries = 0;
            for (int i = 0; i < 1000; i++) {
                clock.addAndGet(1_000_000L);
                ui.answer();
                ui.phase("正在输出");
            }
            assertEquals(4, occurrences(sink.toString(), "fixture-model"), "每秒 1000 个增量只绘制 4 帧底栏");
            assertEquals(8, screen.queries, "只在实际刷新时读取屏幕，不按每个 token 调用原生接口");
            assertFalse(sink.toString().contains("\033[0K"), "缓冲中的 Markdown 不应逐片段擦除底栏");
            assertFalse(sink.toString().contains("◆ YLCoder"), "标题只输出一次");
            sink.getBuffer().setLength(0);
            ui.phase("执行工具");
            assertTrue(sink.toString().contains("执行工具"), "阶段变化必须立即可见");
            sink.getBuffer().setLength(0);
            var usage = new UsageDashboard.Snapshot(1, new TokenUsage(123, 45, 0), 1, 1, 1, 0.0, 0.0, "current-task");
            ui.live(usage, usage, new CommandReview.Stats(0, 0, 0, 0, TokenUsage.ZERO, 0), 1, 2, "执行工具");
            assertTrue(sink.toString().contains("输入 token 123"), "用量事件不受相同阶段节流限制");
            sink.getBuffer().setLength(0);
            ui.beforeOutput();
            writer.println("最终回答");
            ui.phase("执行中");
            assertTrue(sink.toString().indexOf("\033[0K") < sink.toString().indexOf("最终回答"), "实际输出前仍清理底栏");
        }
    }

    @Test void identicalFooterIsSkippedButClearedResizedAndRecoloredFooterIsRepainted() {
        var sink = new StringWriter();
        var screen = new CountingSurface();
        try (var bar = new BottomStatusBar(new PrintWriter(sink), screen)) {
            var lines = List.of("模型 fixture", "输入 123", "缓存 0", "正在输出");
            bar.update(lines, false);
            sink.getBuffer().setLength(0);
            for (int i = 0; i < 1000; i++) bar.update(lines, false);
            assertEquals("", sink.toString(), "内容与几何完全相同不能输出 ANSI 帧");
            bar.beforeOutput();
            sink.getBuffer().setLength(0);
            bar.update(lines, false);
            assertTrue(sink.toString().contains("模型 fixture"), "擦除后必须重绘");
            sink.getBuffer().setLength(0);
            bar.update(lines, true);
            assertTrue(sink.toString().contains("\033[1;35m"), "颜色变化必须重绘");
            sink.getBuffer().setLength(0);
            screen.screen = new BottomStatusBar.Screen(90, 30, 2, 8, 100);
            bar.update(lines, true);
            assertTrue(sink.toString().contains("\033[27;1H"), "尺寸变化按新坐标绘制");
        }
    }

    @Test void inlineStatusFlagIsAvailableOnMainAndInheritedSubcommands() {
        var cli = new HarnessCli();
        new CommandLine(cli).parseArgs();
        assertFalse(cli.inlineStatus, "默认保持固定底栏，滚动布局仅显式选择");
        new CommandLine(cli).parseArgs("--inline-status");
        assertTrue(cli.inlineStatus);
        cli = new HarnessCli();
        new CommandLine(cli).parseArgs("run", "--inline-status", "测试");
        assertTrue(cli.inlineStatus);
    }

    private static int occurrences(String value, String part) {
        return (value.length() - value.replace(part, "").length()) / part.length();
    }

    private static final class CountingSurface implements BottomStatusBar.Surface {
        BottomStatusBar.Screen screen = new BottomStatusBar.Screen(80, 24, 2, 8, 100);
        int queries;
        public BottomStatusBar.Screen screen() { queries++; return screen; }
        public void restore() { }
    }
}
