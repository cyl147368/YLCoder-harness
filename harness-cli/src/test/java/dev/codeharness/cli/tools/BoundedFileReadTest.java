package dev.codeharness.cli.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedFileReadTest {
    @Test void acceptsExactLimitAndRejectsNextByte() throws Exception {
        assertEquals(1_000_000, BoundedFileRead.read(new ByteArrayInputStream(new byte[1_000_000])).length);
        assertThrows(IllegalArgumentException.class,
                () -> BoundedFileRead.read(new ByteArrayInputStream(new byte[1_000_001])));
    }

    @Test void stopsUnboundedSourceAfterSingleExcessByteWithoutTrustingAvailable() {
        AtomicInteger consumed = new AtomicInteger();
        InputStream growing = new InputStream() {
            @Override public int available() { return 0; }
            @Override public int read() { consumed.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] bytes, int offset, int length) {
                consumed.addAndGet(length);
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                return length;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> BoundedFileRead.read(growing));
        assertEquals(1_000_001, consumed.get());
    }

    @Test void observesInterruptionBetweenChunksAndPreservesFlag() {
        InputStream source = new ByteArrayInputStream(new byte[30_000]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> BoundedFileRead.read(source));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
