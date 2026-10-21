package dev.codeharness.cli;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** 在共享 Windows 控制台底部绘制状态；保留全屏原生滚动与历史缓冲区，不接管鼠标输入。 */
final class BottomStatusBar implements AutoCloseable {
    private static final String ESC = "\033[";
    private static final int MAX_ROWS = 5;
    private final PrintWriter writer;
    private final Surface surface;
    private boolean active;
    private boolean closed;
    private Thread shutdownHook;
    private Screen lastScreen;
    private int footerRows;
    private boolean painted;

    static BottomStatusBar open(PrintWriter writer, boolean allowed) {
        if (!allowed || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return new BottomStatusBar(writer, null);
        Surface surface = null;
        try {
            surface = WindowsSurface.open();
            var bar = new BottomStatusBar(writer, surface);
            bar.registerShutdownHook();
            return bar;
        } catch (Throwable unavailable) {
            // 原生 API 缺失、输出重定向或终端不兼容时直接使用普通文本布局。
            if (surface != null) try { surface.restore(); } catch (Throwable ignored) { }
            return new BottomStatusBar(writer, null);
        }
    }

    /** 可注入屏幕信息，在不调用原生接口的情况下验证重绘与恢复。 */
    BottomStatusBar(PrintWriter writer, Surface surface) {
        this.writer = java.util.Objects.requireNonNull(writer);
        this.surface = surface;
        if (surface == null) return;
        try {
            Screen screen = surface.screen();
            if (!usable(screen, 1)) { surface.restore(); return; }
            active = true;
        } catch (Throwable unavailable) {
            try { surface.restore(); } catch (Throwable ignored) { }
        }
    }

    synchronized boolean active() { return active && !closed; }

    private synchronized void registerShutdownHook() {
        if (!active()) return;
        try {
            shutdownHook = new Thread(this::close, "ylc-console-restore");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException unavailable) {
            shutdownHook = null;
            disable();
        }
    }

    synchronized void update(List<String> lines) {
        update(lines, false);
    }

    synchronized void update(List<String> lines, boolean color) {
        if (!active()) return;
        try {
            Screen screen = surface.screen();
            // 向上翻阅时光标会落在可见视窗之外。此时不能用光标控制强行跳回底部，
            // 也不能把历史视窗当作失效终端去清理；返回实时视图后可在下次更新重绘。
            if (browsingHistory(screen)) return;
            // 一行轻分隔和最多四行状态共同占用预留区；正文不能进入分隔行。
            int rows = 1 + Math.min(MAX_ROWS - 1, Math.max(1, lines.size()));
            if (!usable(screen, rows)) { disable(); return; }
            boolean changed = lastScreen == null || rows != footerRows
                    || screen.width() != lastScreen.width() || screen.height() != lastScreen.height()
                    || screen.windowTop() != lastScreen.windowTop()
                    // 正文使用全屏原生滚动；缓冲区已满时 windowTop 也不会再增长。
                    // 每次检查实际光标，先为刚输出的正文腾出空间，不能直接覆盖底部几行。
                    || screen.cursorRow() > screen.height() - rows;
            if (changed) establish(screen, rows);
            writer.print("\0337");
            int start = screen.height() - footerRows + 1;
            for (int index = 0; index < footerRows; index++) {
                writer.print(ESC + (start + index) + ";1H" + ESC + "2K");
                if (index == 0) {
                    // 分隔线由程序生成，保留右侧安全列；不接受外部颜色或控制序列。
                    writer.print(tint("  " + "─".repeat(screen.width() - 3), "90", color));
                } else if (index - 1 < lines.size()) {
                    writer.print(paintLine(fit(lines.get(index - 1), screen.width() - 1), index - 1, color));
                }
            }
            writer.print("\0338");
            writer.flush();
            if (writer.checkError()) disable();
            else {
                painted = true;
                lastScreen = surface.screen();
            }
        } catch (Throwable unavailable) {
            disable();
        }
    }

    /** 先清理并裁剪纯文本，再由固定调色板着色；绝不接纳配置中的 ANSI 指令。 */
    static String paintLine(String text, int row, boolean color) {
        if (!color) return text;
        String[] parts = text.split(" · ", -1);
        var output = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) output.append(tint(" · ", "90", true));
            String code = switch (row) {
                case 0 -> index == 0 ? "1;35" : "36";
                case 1 -> index == 0 ? "36" : "35";
                case 2 -> text.contains("未报告") || text.contains("尚无请求") ? "90" : "32";
                default -> index > 0 ? (parts[index].contains("联网") ? "36" : "90")
                        : text.contains("未完成") ? "31" : text.contains("取消") || text.contains("上限") ? "33"
                        : text.contains("思考") || text.contains("执行") || text.contains("输出") || text.contains("重试") ? "33" : "32";
            };
            output.append(tint(parts[index], code, true));
        }
        return output.toString();
    }

    private static String tint(String value, String code, boolean color) {
        return color ? ESC + code + "m" + value + ESC + "0m" : value;
    }

    /** 正文输出前清除仍属于底栏的文字，避免新短行留下旧字段的尾巴。 */
    synchronized void beforeOutput() {
        if (!active() || !painted) return;
        try {
            Screen screen = surface.screen();
            if (screen == null || browsingHistory(screen)) return;
            if (lastScreen != null && screen.width() == lastScreen.width()
                    && screen.height() == lastScreen.height() && screen.windowTop() == lastScreen.windowTop()) {
                writer.print("\0337");
                int first = Math.max(screen.height() - footerRows + 1, screen.cursorRow());
                for (int row = first; row <= screen.height(); row++) {
                    // 输入回显可能已经占据底栏的部分行，只清除光标之后，保留用户刚输入的内容。
                    int column = row == screen.cursorRow() ? screen.cursorColumn() : 1;
                    writer.print(ESC + row + ";" + column + "H" + ESC + "0K");
                }
                writer.print("\0338");
                writer.flush();
            }
            painted = false;
        } catch (Throwable unavailable) { disable(); }
    }

    private boolean browsingHistory(Screen screen) {
        return screen != null && (screen.cursorRow() < 1 || screen.cursorRow() > screen.height()
                // 仅向上滚一两行时输入光标仍在视窗内，也必须尊重用户的历史位置。
                || lastScreen != null && screen.width() == lastScreen.width()
                && screen.height() == lastScreen.height() && screen.windowTop() < lastScreen.windowTop());
    }

    private void establish(Screen screen, int rows) {
        int column = screen.cursorColumn();
        int row = screen.cursorRow();
        // 不设置 DECSTBM 局部滚动区：局部滚动会让 Windows Terminal 等终端丢失历史。
        // windowTop 可随输出移动；旧缓冲区行可能已成为正文，绝不能按旧坐标擦除。
        int contentRows = screen.height() - rows;
        if (row > contentRows) {
            // 先在完整屏幕末尾向上滚动，为状态区腾出空白，防止覆盖刚输出的正文。
            int scroll = row - contentRows;
            writer.print(ESC + screen.height() + ";1H");
            writer.print("\n".repeat(scroll));
            row -= scroll;
        }
        writer.print(ESC + Math.min(contentRows, row) + ";" + column + "H");
        footerRows = rows;
        lastScreen = screen;
    }

    private static boolean usable(Screen screen, int rows) {
        return screen != null && screen.width() >= 80 && screen.height() >= rows + 6
                && screen.cursorColumn() >= 1 && screen.cursorColumn() <= screen.width()
                && screen.cursorRow() >= 1 && screen.cursorRow() <= screen.height();
    }

    private void disable() {
        if (!active) return;
        active = false;
        try {
            // 没有局部滚动区需要重置；不发光标控制，关闭时也能留在用户正在翻阅的位置。
            writer.flush();
        } finally {
            try { surface.restore(); } catch (Throwable ignored) { }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        try { disable(); } finally {
            closed = true;
            Thread hook = shutdownHook;
            shutdownHook = null;
            if (hook != null && Thread.currentThread() != hook) {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException | SecurityException ignored) {
                    // JVM 已经退出时，已注册的钩子再次 close 也不会重复恢复。
                }
            }
        }
    }

    /** 按终端显示列裁剪，并清理所有控制字符，避免状态文字改变光标或滚动区。 */
    static String fit(String text, int columns) {
        if (text == null || columns <= 0) return "";
        var result = new StringBuilder();
        int used = 0;
        var codes = text.codePoints().iterator();
        while (codes.hasNext()) {
            int code = codes.nextInt();
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT
                    || code == 0x2028 || code == 0x2029) code = ' ';
            int width = width(code);
            if (used + width > columns) break;
            result.appendCodePoint(code);
            used += width;
        }
        return result.toString();
    }

    private static int width(int code) {
        int type = Character.getType(code);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || code >= 0xfe00 && code <= 0xfe0f || code >= 0xe0100 && code <= 0xe01ef) return 0;
        return code >= 0x1100 && (code <= 0x115f || code == 0x2329 || code == 0x232a
                || code >= 0x2e80 && code <= 0xa4cf && code != 0x303f
                || code >= 0xac00 && code <= 0xd7a3 || code >= 0xf900 && code <= 0xfaff
                || code >= 0xfe10 && code <= 0xfe19 || code >= 0xfe30 && code <= 0xfe6f
                || code >= 0xff00 && code <= 0xff60 || code >= 0xffe0 && code <= 0xffe6
                || code >= 0x1f300 && code <= 0x1faff || code >= 0x20000 && code <= 0x3fffd) ? 2 : 1;
    }

    interface Surface {
        Screen screen();
        void restore();
    }

    /** 列和行均从 1 开始，窗口顶端使用缓冲区的从 0 开始坐标。 */
    record Screen(int width, int height, int cursorColumn, int cursorRow, int windowTop) { }

    private static final class WindowsSurface implements Surface {
        private final Kernel kernel;
        private final Pointer output;
        private final int originalMode;
        private boolean restored;

        private WindowsSurface(Kernel kernel, Pointer output, int originalMode) {
            this.kernel = kernel;
            this.output = output;
            this.originalMode = originalMode;
        }

        static WindowsSurface open() {
            Kernel kernel = Native.load("kernel32", Kernel.class);
            Pointer input = kernel.GetStdHandle(-10), output = kernel.GetStdHandle(-11), error = kernel.GetStdHandle(-12);
            IntByReference inMode = new IntByReference(), outMode = new IntByReference(), errMode = new IntByReference();
            if (!valid(input) || !valid(output) || !valid(error) || !kernel.GetConsoleMode(input, inMode)
                    || !kernel.GetConsoleMode(output, outMode) || !kernel.GetConsoleMode(error, errMode)) return null;
            // 句柄值可以不同；必须确认二者确实指向同一个控制台缓冲区。
            ObjectHandles objects = Native.load("kernelbase", ObjectHandles.class);
            if (!objects.CompareObjectHandles(output, error)) return null;
            var surface = new WindowsSurface(kernel, output, outMode.getValue());
            if (!usable(surface.screen(), 1)) return null;
            if (!kernel.SetConsoleMode(output, outMode.getValue() | 0x0001 | 0x0004)) return null;
            return surface;
        }

        private static boolean valid(Pointer handle) {
            return handle != null && Pointer.nativeValue(handle) != 0 && Pointer.nativeValue(handle) != -1L;
        }

        @Override public Screen screen() {
            var info = new BufferInfo();
            if (!kernel.GetConsoleScreenBufferInfo(output, info)) throw new IllegalStateException("无法读取控制台尺寸");
            int left = info.windowLeft, top = info.windowTop;
            int width = info.windowRight - left + 1, height = info.windowBottom - top + 1;
            // 横向滚动的窗口坐标不适合 VT 状态区，宁可退回普通输出。
            if (left != 0 || info.sizeX != width) throw new IllegalStateException("控制台存在横向滚动");
            return new Screen(width, height, info.cursorX - left + 1, info.cursorY - top + 1, top);
        }

        @Override public void restore() {
            if (!restored) { restored = true; kernel.SetConsoleMode(output, originalMode); }
        }
    }

    @Structure.FieldOrder({"sizeX", "sizeY", "cursorX", "cursorY", "attributes", "windowLeft", "windowTop", "windowRight", "windowBottom", "maxX", "maxY"})
    public static final class BufferInfo extends Structure {
        public short sizeX, sizeY, cursorX, cursorY, attributes;
        public short windowLeft, windowTop, windowRight, windowBottom, maxX, maxY;
    }

    interface Kernel extends StdCallLibrary {
        Pointer GetStdHandle(int kind);
        boolean GetConsoleMode(Pointer handle, IntByReference mode);
        boolean SetConsoleMode(Pointer handle, int mode);
        boolean GetConsoleScreenBufferInfo(Pointer handle, BufferInfo info);
    }

    interface ObjectHandles extends StdCallLibrary {
        boolean CompareObjectHandles(Pointer first, Pointer second);
    }
}
