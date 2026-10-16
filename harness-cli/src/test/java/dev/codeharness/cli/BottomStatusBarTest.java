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
            assertTrue(emitted.contains("\033[1;21r\033[10;7H"), "滚动区使用 24 行视窗，而不是缓冲区总高度");
            assertTrue(emitted.contains("\0337\033[22;1H\033[2K模型 fixture"));
            assertTrue(emitted.endsWith("\0338"), "重绘后返回正文光标");
        }
        assertTrue(sink.toString().endsWith("\0337\033[r\0338"), "退出恢复滚动区后也要恢复当时的真实光标");
        assertEquals(1, surface.restores);
    }

    @Test void initializationScrollsFinishedBodyAboveFooterBeforePainting() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 4, 24, 0));
        var bar = new BottomStatusBar(new PrintWriter(sink), surface);
        bar.update(List.of("a", "b", "c"));
        assertTrue(sink.toString().startsWith("\033[24;1H\n\n\n\033[1;21r\033[21;4H"));
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
            assertTrue(sink.toString().contains("\033[1;28r\033[8;2H"));
            assertTrue(sink.toString().contains("\033[29;1H\033[2Knew"));
            assertEquals(Set.of(29, 30), erasedRows(sink.toString()),
                    "resize 后旧状态的绝对行也可能属于新正文，只能绘制新预留区");
        }
    }

    @Test void ordinaryViewportScrollNeverErasesBodyAtPreviousAbsoluteFooterRows() {
        var sink = new StringWriter();
        var surface = new FakeSurface(new BottomStatusBar.Screen(80, 24, 2, 8, 100));
        try (var bar = new BottomStatusBar(new PrintWriter(sink), surface)) {
            bar.update(List.of("model", "usage", "cache", "phase"));
            sink.getBuffer().setLength(0);
            // 正文和人工审批将视窗向下推进八行；旧状态的绝对行如今落在 13..16 行正文。
            surface.screen = new BottomStatusBar.Screen(80, 24, 4, 12, 108);
            bar.update(List.of("new model", "new usage", "new cache", "new phase"));
            assertEquals(Set.of(21, 22, 23, 24), erasedRows(sink.toString()),
                    "旧的 13..16 行可能含任务标题、命令与审批提示，不能清除");
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
        assertEquals("\0337\033[r\0338", sink.toString());
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
            String reserve = "\033[r\033[24;1H\n\n\n\n\033[1;20r\033[20;9H";
            assertTrue(emitted.startsWith(reserve), "即使几何不变，也必须先把正文末四行向上腾出空间");
            assertEquals(Set.of(21, 22, 23, 24), erasedRows(emitted));
            assertTrue(emitted.indexOf("\033[21;1H\033[2K") > emitted.indexOf(reserve),
                    "只允许在正文已经移出后绘制状态区");
        }
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
            assertEquals("\0337\033[r\0338", sink.toString());
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
