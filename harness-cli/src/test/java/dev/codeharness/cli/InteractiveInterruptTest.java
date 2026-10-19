package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveInterruptTest {
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
        private boolean restored;
        public String readLine() { return null; }
        public boolean isTerminal() { return true; }
        public Runnable onInterrupt(Runnable handler) {
            this.handler = handler;
            return () -> restored = true;
        }
    }
}
