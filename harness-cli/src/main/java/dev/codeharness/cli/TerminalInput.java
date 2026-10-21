package dev.codeharness.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 任务行和权限审批共用一个输入所有者。永不关闭进程级 stdin。 */
interface TerminalInput {
    String readLine() throws IOException;
    boolean isTerminal();
    default boolean editsLine() { return false; }
    default String readCommand(String prompt, java.util.function.Supplier<java.util.List<String>> candidates) throws IOException {
        return readLine();
    }
    default void closeEditor() throws IOException { }
    /** 注册会话取消处理器；返回用于恢复原处理器的操作。 */
    default Runnable onInterrupt(Runnable handler) { return () -> { }; }
    /** Esc 不代表 EOF；任务执行期间由唯一的按键读取器接管输入。 */
    default Runnable onEscape(Runnable handler) { return () -> { }; }
    default void startTaskInput() { }
    default void stopTaskInput() { }

    static TerminalInput system() {
        var console = System.console();
        if (console != null) {
            TerminalInput editor = CompletingTerminalInput.open();
            if (editor != null) return editor;
            // Windows 的窄字符输入在 UTF-8 代码页下会丢失中文，直接读取 UTF-16 控制台事件。
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                return withSignals(lines(new BufferedReader(new WindowsConsoleReader()), true));
            }
            return withSignals(new TerminalInput() {
                public String readLine() { return console.readLine(); }
                public boolean isTerminal() { return true; }
            });
        }
        return utf8(System.in);
    }

    private static TerminalInput withSignals(TerminalInput input) {
        return new TerminalInput() {
            public String readLine() throws IOException { return input.readLine(); }
            public boolean isTerminal() { return input.isTerminal(); }
            public Runnable onInterrupt(Runnable handler) {
                Object previous = org.jline.utils.Signals.register("INT", handler);
                return () -> org.jline.utils.Signals.unregister("INT", previous);
            }
        };
    }

    static TerminalInput utf8(InputStream input) {
        // 对无效字节明确报错，避免替换后的乱码被当成任务发送给模型。
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return lines(new BufferedReader(new InputStreamReader(input, decoder)), false);
    }

    private static TerminalInput lines(BufferedReader reader, boolean terminal) {
        return new TerminalInput() {
            public String readLine() throws IOException { return reader.readLine(); }
            public boolean isTerminal() { return terminal; }
        };
    }
}

/** 只在真实 Windows 控制台首次读取时加载原生库；不关闭共享的标准输入句柄。 */
final class WindowsConsoleReader extends Reader {
    private WindowsKernel kernel;
    private com.sun.jna.Pointer handle;

    @Override
    public int read(char[] target, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        initialize();
        int capacity = Math.min(length, 4096);
        try (var memory = new com.sun.jna.Memory(capacity * 2L)) {
            var read = new com.sun.jna.ptr.IntByReference();
            if (!kernel.ReadConsoleW(handle, memory, capacity, read, null)) {
                throw new IOException("读取 Windows 控制台失败，错误代码：" + com.sun.jna.Native.getLastError());
            }
            int count = read.getValue();
            if (count == 0) return -1;
            for (int index = 0; index < count; index++) {
                target[offset + index] = (char) (memory.getShort(index * 2L) & 0xffff);
            }
            // 保留 Windows 命令行通用的 Ctrl+Z 后回车结束输入行为。
            if (target[offset] == 0x1a && (count == 1 || target[offset + 1] == '\r' || target[offset + 1] == '\n')) return -1;
            return count;
        }
    }

    private void initialize() throws IOException {
        if (kernel != null) return;
        try {
            var loaded = com.sun.jna.Native.load("kernel32", WindowsKernel.class);
            var input = loaded.GetStdHandle(-10);
            var mode = new com.sun.jna.ptr.IntByReference();
            if (input == null || com.sun.jna.Pointer.nativeValue(input) == -1L || !loaded.GetConsoleMode(input, mode)) {
                throw new IOException("标准输入未连接 Windows 控制台。");
            }
            handle = input;
            kernel = loaded;
        } catch (LinkageError | SecurityException error) {
            throw new IOException("无法初始化 Windows 中文输入，请重新安装完整发行包。", error);
        }
    }

    @Override
    public void close() { /* 由任务行和审批共用，禁止关闭进程级输入。 */ }

    interface WindowsKernel extends com.sun.jna.win32.StdCallLibrary {
        com.sun.jna.Pointer GetStdHandle(int kind);
        boolean GetConsoleMode(com.sun.jna.Pointer handle, com.sun.jna.ptr.IntByReference mode);
        boolean ReadConsoleW(com.sun.jna.Pointer handle, com.sun.jna.Pointer buffer, int count,
                             com.sun.jna.ptr.IntByReference read, com.sun.jna.Pointer control);
    }
}
