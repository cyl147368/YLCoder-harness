package dev.codeharness.cli;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import java.io.IOException;
import java.util.function.Consumer;

/** 任务期间读取 Esc；停止并交还输入之后，行编辑器才能读取审批或下一项任务。 */
final class TaskKeyMonitor implements AutoCloseable {
    private final Terminal terminal;
    private final Runnable escape;
    private final Consumer<String> replay;
    private final StringBuilder pending = new StringBuilder();
    private final Attributes previous;
    private final Thread worker;
    private volatile boolean active = true;

    TaskKeyMonitor(Terminal terminal, Runnable escape, Consumer<String> replay) {
        this.terminal = terminal;
        this.escape = escape;
        this.replay = replay;
        previous = terminal.enterRawMode();
        worker = Thread.ofVirtual().name("ylc-escape").start(this::read);
    }

    private void read() {
        try {
            var input = terminal.reader();
            while (active) {
                int key = input.read(50);
                if (key == NonBlockingReader.READ_EXPIRED) continue;
                if (key < 0) break;
                if (key != 27) { pending.append((char) key); continue; }
                int next = input.read(80);
                if (next == 27 || next < 0) {
                    pending.setLength(0); // 取消时丢弃未提交的预输入，避免意外发送下一项任务。
                    escape.run();
                    if (next == 27) escape.run();
                } else {
                    // 方向键、功能键、鼠标或粘贴起始符是完整转义序列，不能当成 Esc。
                    pending.append((char) key).append((char) next);
                    if (next == '[' || next == 'O') {
                        while (active) {
                            int part = input.read(50);
                            if (part == NonBlockingReader.READ_EXPIRED) continue;
                            if (part < 0) break;
                            pending.append((char) part);
                            if (part >= 0x40 && part <= 0x7e) break;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 断开终端的错误由随后恢复的行编辑器处理；监视线程不输出或改变会话。
        }
    }

    @Override public void close() {
        active = false;
        boolean interrupted = Thread.interrupted();
        while (worker.isAlive()) {
            try { worker.join(); }
            catch (InterruptedException cancelled) { interrupted = true; }
        }
        try {
            terminal.setAttributes(previous);
            if (!pending.isEmpty()) replay.accept(pending.toString());
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
