package dev.codeharness.cli;

import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真正的 JLine 编辑器验证预填显示与编辑，不以接口收到参数代替屏幕验收。 */
@Timeout(10)
class CompletingTerminalInputTest {
    @Test void 回退提问在回车前已经显示且可以编辑() throws Exception {
        try (var keyboard = new PipedOutputStream(); var input = new PipedInputStream(keyboard);
             var terminal = new DumbTerminal("fixture", "xterm", input, new Screen(), StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(100, 30));
            var screen = (Screen) terminal.output();
            var editor = new CompletingTerminalInput(terminal);
            var task = new FutureTask<>(() -> editor.readCommand("你 › ", List::of, "修复中文问题😀"));
            Thread.ofVirtual().start(task);
            screen.await("修复中文问题😀");
            assertFalse(task.isDone(), "显示草稿不能自动提交任务");
            keyboard.write("\177并补充测试\r".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            assertEquals("修复中文问题并补充测试", task.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void 多行回退草稿保留全文且不把换行当成提交() throws Exception {
        try (var keyboard = new PipedOutputStream(); var input = new PipedInputStream(keyboard);
             var terminal = new DumbTerminal("fixture", "xterm", input, new Screen(), StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(100, 30));
            var screen = (Screen) terminal.output();
            var editor = new CompletingTerminalInput(terminal);
            String draft = "第一行中文\n第二行需求";
            var task = new FutureTask<>(() -> editor.readCommand("你 › ", List::of, draft));
            Thread.ofVirtual().start(task);
            screen.await("第二行需求");
            assertFalse(task.isDone());
            keyboard.write('\r');
            keyboard.flush();
            assertEquals(draft, task.get(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\033[Z", "\033m"})
    void 审核快捷键请求切换模式并保留输入草稿(String sequence) throws Exception {
        try (var keyboard = new PipedOutputStream(); var input = new PipedInputStream(keyboard);
             var terminal = new DumbTerminal("fixture", "xterm", input, new Screen(), StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(100, 30));
            var editor = new CompletingTerminalInput(terminal);
            var task = new FutureTask<>(() -> editor.readCommand("你 › ", List::of, "保留这段输入"));
            Thread.ofVirtual().start(task);
            ((Screen) terminal.output()).await("保留这段输入");
            keyboard.write(sequence.getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            assertEquals("", task.get(3, TimeUnit.SECONDS));
            assertTrue(editor.takeApprovalModeToggle());
            assertEquals("保留这段输入", editor.takeInputDraft());
            assertFalse(editor.takeApprovalModeToggle());
            assertNull(editor.takeInputDraft());
        }
    }

    @Test void 审批框的ShiftTab不会提交答案或切换权限() throws Exception {
        try (var keyboard = new PipedOutputStream(); var input = new PipedInputStream(keyboard);
             var terminal = new DumbTerminal("fixture", "xterm", input, new Screen(), StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(100, 30));
            var editor = new CompletingTerminalInput(terminal);
            var task = new FutureTask<>(() -> editor.readCommand("允许执行命令吗？[y/N] ", List::of));
            Thread.ofVirtual().start(task);
            ((Screen) terminal.output()).await("[y/N]");
            keyboard.write("\033[Zy\r".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            assertEquals("y", task.get(3, TimeUnit.SECONDS));
            assertFalse(editor.takeApprovalModeToggle());
            assertNull(editor.takeInputDraft());
        }
    }

    private static final class Screen extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) { super.write(value); notifyAll(); }
        @Override public synchronized void write(byte[] value, int start, int count) { super.write(value, start, count); notifyAll(); }
        synchronized void await(String expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!toString(StandardCharsets.UTF_8).contains(expected)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) fail("屏幕未显示预填内容：" + toString(StandardCharsets.UTF_8));
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
    }
}
