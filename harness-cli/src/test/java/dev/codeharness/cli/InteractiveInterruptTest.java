package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveInterruptTest {
    @Test void escapeCancelsOnlyTaskAndDoubleEscapeQueuesOneRewind() throws Exception {
        var input = new Input();
        try (var cancellation = new InteractiveInterrupt(input)) {
            cancellation.beginTask();
            Thread key = Thread.ofPlatform().unstarted(input.escape);
            key.start();
            // 信号线程中断当前执行线程；join 可能立即抛出，随后显式清理用于写检查点。
            try { key.join(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            cancellation.finishTask();
            key.join();
            assertTrue(cancellation.cancelled());
            assertFalse(cancellation.requested());
            assertFalse(Thread.currentThread().isInterrupted());
            input.escape.run();
            assertTrue(cancellation.takeRewind());
            assertFalse(cancellation.takeRewind());
            cancellation.beginTask();
            assertFalse(cancellation.cancelled(), "下一项任务不能继承取消状态");
            cancellation.finishTask();
        } finally { Thread.interrupted(); }
    }

    @Test void idleEscapeNeverInterruptsSavingAndSameThreadApprovalCanCancel() {
        var input = new Input();
        try (var cancellation = new InteractiveInterrupt(input)) {
            input.escape.run();
            assertFalse(cancellation.cancelled());
            assertFalse(Thread.currentThread().isInterrupted());
            cancellation.beginTask();
            input.escape.run();
            assertTrue(cancellation.cancelled());
            assertFalse(Thread.currentThread().isInterrupted());
            cancellation.finishTask();
            input.escape.run();
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
    @Test void repeatedSignalsCannotInterruptCheckpointOrTerminalCleanup() {
        var input = new Input();
        try (var cancellation = new InteractiveInterrupt(input)) {
            input.handler.run();
            assertTrue(cancellation.requested());
            assertTrue(Thread.currentThread().isInterrupted());
            cancellation.finish();
            assertFalse(Thread.currentThread().isInterrupted());
            input.handler.run();
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertTrue(input.restored);
    }

    private static final class Input implements TerminalInput {
        private Runnable handler;
        private Runnable escape;
        private boolean restored;
        public String readLine() { return null; }
        public boolean isTerminal() { return true; }
        public Runnable onInterrupt(Runnable handler) {
            this.handler = handler;
            return () -> restored = true;
        }
        public Runnable onEscape(Runnable handler) { escape = handler; return () -> { }; }
    }
}
