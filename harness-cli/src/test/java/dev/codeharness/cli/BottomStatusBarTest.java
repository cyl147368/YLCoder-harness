package dev.codeharness.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BottomStatusBarTest {
    @Test void reservesViewportRowsAndRestoresCursorWithoutTouchingInput() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 7, 10, 300));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            assertTrue(bar.active());
            bar.update(List.of("模型 fixture", "输入 200 / 输出 10 / 缓存 100", "状态：等待输入"));
            String emitted = sink.toString();
            assertTrue(emitted.startsWith("\033[10;7H"), "重绘应从当前正文光标出发");
            assertFalse(Pattern.compile("\\x1b\\[[0-9;]*r").matcher(emitted).find(),
                    "不能设置局部滚动区域，否则向上滚出的正文无法进入终端历史");
            assertTrue(emitted.contains("\033[22;1H\033[2K模型 fixture"));
            assertTrue(emitted.endsWith("\0338"), "重绘后返回正文光标");
        }
        assertTrue(sink.toString().endsWith("\0338"), "关闭不移动用户正在查看的视窗");
        assertEquals(1, surface.restores);
    }

    @Test void initializationScrollsFinishedBodyAboveFooterBeforePainting() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 4, 24, 0));
        var bar = new BottomStatusBar(new PrintWriter(sink), surface);
        bar.update(List.of("a", "b", "c"));
        assertTrue(sink.toString().startsWith("\033[24;1H\n\n\n\n\033[20;4H"));
        bar.close();
        bar.close();
        assertEquals(1, surface.restores, "关闭必须幂等");
    }

    @Test void resizeRebuildsRegionWithoutErasingOldFooterCoordinates() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("old", "line"));
            sink.getBuffer().setLength(0);
            surface.screen = new BottomStatusBar.Screen(90, 30, 2, 8, 100);
            bar.update(List.of("new", "line"));
            assertTrue(sink.toString().startsWith("\033[8;2H"));
            assertTrue(sink.toString().contains("\033[29;1H\033[2Knew"));
            assertEquals(Set.of(28, 29, 30), erasedRows(sink.toString()),
                    "resize 后旧状态的绝对行也可能属于新正文，只能绘制新预留区");
        }
    }

    @Test void ordinaryViewportScrollNeverErasesBodyAtPreviousAbsoluteFooterRows() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            // 正文和人工审批将视窗向下推进八行；旧状态的绝对行如今落在 12..16 行正文。
            surface.screen = new BottomStatusBar.Screen(80, 24, 4, 12, 108);
            bar.update(List.of("new model", "new usage", "new cache", "new phase"));
            assertEquals(Set.of(20, 21, 22, 23, 24), erasedRows(sink.toString()),
                    "旧的 12..16 行可能含任务标题、命令与审批提示，不能清除");
            assertTrue(sink.toString().contains("\033[12;4H"), "重建后恢复最新正文光标");
        }
    }

    @Test void closeAfterGeometryChangesPreservesEveryUnknownLine() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 100));
        var bar = new BottomStatusBar(new PrintWriter(sink), surface);
        bar.update(List.of("model", "usage", "cache", "phase"));
        sink.getBuffer().setLength(0);
        surface.screen = new BottomStatusBar.Screen(90, 18, 2, 8, 108);
        bar.close();
        assertEquals(Set.of(), erasedRows(sink.toString()), "关闭不能按过期坐标清除正文");
        assertEquals("", sink.toString());
        assertEquals(1, surface.restores);
    }

    @Test void fullBufferStillMakesRoomWhenBodyCursorEntersFooterWithoutGeometryChange() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 276));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            // 缓冲区已经饱和：新的正文到达最底行，windowTop 与尺寸完全不变。
            surface.screen = new BottomStatusBar.Screen(80, 24, 9, 24, 276);
            bar.update(List.of("new model", "new usage", "new cache", "new phase"));
            String emitted = sink.toString();
            String reserve = "\033[24;1H\n\n\n\n\n\033[19;9H";
            assertTrue(emitted.startsWith(reserve), "即使几何不变，也必须先为分隔行及四行状态腾出空间");
            assertEquals(Set.of(20, 21, 22, 23, 24), erasedRows(emitted));
            assertTrue(emitted.indexOf("\033[20;1H\033[2K") > emitted.indexOf(reserve),
                    "只允许在正文已经移出后绘制状态区");
        }
    }

    @Test void separatorUsesSafeWidthAndTotalFooterNeverExceedsFiveRows() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 11, 1, 2, 0));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase", "must not consume a sixth row"));
            String emitted = sink.toString();
            assertTrue(emitted.startsWith("\033[2;1H"), "绘制底栏时保持当前正文光标位置");
            assertEquals(Set.of(7, 8, 9, 10, 11), erasedRows(emitted));
            var separator = Pattern.compile("\\x1b\\[7;1H\\x1b\\[2K([^\\x1b]*)").matcher(emitted);
            assertTrue(separator.find());
            assertTrue(separator.group(1).startsWith("  "), "分隔线与正文状态统一缩进");
            assertEquals(79, separator.group(1).length(), "最后一列留白，避免自动换行侵入正文");
            assertFalse(emitted.contains("must not consume"));

            sink.getBuffer().setLength(0);
            surface.screen = new BottomStatusBar.Screen(80, 10, 1, 2, 0);
            bar.update(List.of("model", "usage", "cache", "phase"));
            assertFalse(bar.active(), "分隔线也计入最小高度，小屏直接回退");
            assertEquals(Set.of(), erasedRows(sink.toString()), "高度不足时不擦除任何正文");
        }
        assertEquals(1, surface.restores);
    }

    @Test void degradationAfterShrinkRestoresModesWithoutErasingBody() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            surface.screen = new BottomStatusBar.Screen(70, 12, 1, 8, 108);
            bar.update(List.of("model", "usage", "cache", "phase"));
            assertFalse(bar.active());
            assertEquals(Set.of(), erasedRows(sink.toString()));
            assertEquals("", sink.toString());
        }
        assertEquals(1, surface.restores);
    }

    @Test void smallOrFailedSurfaceFallsBackAndRestoresOnce() {
        var sink = new StringWriter();
        var small = new FakeSurface(new BottomStatusBar.Screen(20, 5, 1, 1, 0));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), small)) {
            assertFalse(bar.active());
            bar.update(List.of("not rendered"));
        }
        assertEquals("", sink.toString());
        assertEquals(1, small.restores);

        var failing = new FakeSurface(new BottomStatusBar.Screen(80, 24, 1, 2, 0));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), failing)) {
            bar.update(List.of("rendered"));
            failing.fail = true;
            assertDoesNotThrow(() -> bar.update(List.of("not rendered")));
            assertFalse(bar.active());
        }
        assertEquals(1, failing.restores);
    }

    @Test void scrollingThroughHistorySuspendsPaintingWithoutDisablingFooter() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 19, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            surface.screen = new BottomStatusBar.Screen(80, 24, 2, 22, 97);
            bar.beforeOutput();
            bar.update(List.of("new model", "new usage", "new cache", "new phase"));
            assertEquals("", sink.toString(), "只上滚三行、输入光标仍可见时也不能强行回到底部");
            surface.screen = new BottomStatusBar.Screen(80, 24, 2, 49, 70);
            bar.beforeOutput();
            bar.update(List.of("new model", "new usage", "new cache", "new phase"));
            assertEquals("", sink.toString(), "翻阅历史时不能写光标控制序列或覆盖旧正文");
            assertTrue(bar.active(), "翻阅历史只是暂缓绘制，不能永久禁用状态栏");
            assertEquals(0, surface.restores);
            surface.screen = new BottomStatusBar.Screen(80, 24, 2, 19, 100);
            bar.update(List.of("latest model", "usage", "cache", "phase"));
            assertTrue(sink.toString().contains("latest model"), "回到底部后的下一次更新应恢复状态");
            sink.getBuffer().setLength(0);
            surface.screen = new BottomStatusBar.Screen(80, 24, 2, 49, 70);
        }
        assertEquals("", sink.toString(), "在历史视窗退出也不能强行回到底部");
        assertEquals(1, surface.restores);
    }

    @Test void outputClearsOnlyFooterSuffixAfterEchoedInputAndOnlyOnce() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 19, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            // 多行输入回显已写入第20行和第21行前10列，不允许清除这些用户文字。
            surface.screen = new BottomStatusBar.Screen(80, 24, 11, 21, 100);
            bar.beforeOutput();
            String cleared = sink.toString();
            assertEquals("\0337\033[21;11H\033[0K\033[22;1H\033[0K\033[23;1H\033[0K\033[24;1H\033[0K\0338", cleared);
            assertFalse(cleared.contains("20;"));
            bar.beforeOutput();
            assertEquals(cleared, sink.toString(), "底栏已清除时不能再次擦除即将输出的正文");
        }
    }

    @Test void disabledFactoryDoesNotEmitAnyTerminalControl() {
        var sink = new StringWriter();
        try (var bar = BottomStatusBar.open(new PrintWriter(sink), false)) {
            bar.update(List.of("status"));
            assertFalse(bar.active());
        }
        assertEquals("", sink.toString());
    }

    @Test void truncationCountsCjkAndNeverSplitsSurrogatesOrLetsControlsThrough() {
        assertEquals("模型 ab", BottomStatusBar.fit("模型 abcdef", 7));
        assertEquals("中", BottomStatusBar.fit("中文", 3));
        assertEquals("😀", BottomStatusBar.fit("😀x", 2));
        assertEquals("a\u0301", BottomStatusBar.fit("a\u0301b", 1));
        assertEquals("  [2J x", BottomStatusBar.fit("\n\033[2J\u200fx", 30));
        assertEquals("", BottomStatusBar.fit(null, 30));
    }

    /** 记录实际清行序列的目标；直接检查集合可捕获旧坐标回擦正文的回归。 */
    private static Set<Integer> erasedRows(String output) {
        var pattern = Pattern.compile("\\x1b\\[(\\d+);\\d+H\\x1b\\[2K");
        var matcher = pattern.matcher(output);
        var rows = new LinkedHashSet<Integer>();
        while (matcher.find()) rows.add(Integer.parseInt(matcher.group(1)));
        return rows;
    }

    private static final class FakeSurface implements BottomStatusBar.Surface {
        BottomStatusBar.Screen screen;
        int restores;
        boolean fail;
        FakeSurface(BottomStatusBar.Screen screen) { this.screen = screen; }
        public BottomStatusBar.Screen screen() {
            if (fail) throw new IllegalStateException("模拟终端消失");
            return screen;
        }
        public void restore() { restores++; }
    }
}
