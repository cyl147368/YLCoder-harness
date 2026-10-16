package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CommandProcessCleanupTest {
    @Test void stalledDescendantEnumerationCannotDelayDirectTerminationOrCaller() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var cleanupDaemon = new AtomicBoolean();
        var process = new FakeProcess() {
            @Override public Stream<ProcessHandle> descendants() {
                cleanupDaemon.set(Thread.currentThread().isDaemon());
                entered.countDown();
                try { hold(release); return Stream.empty(); }
                finally { finished.countDown(); }
            }
        };
        try {
            runBounded(() -> CommandProcessCleanup.stop(process));
            await(entered);
            await(process.destroyed);
            assertTrue(cleanupDaemon.get());
            assertEquals(1, finished.getCount(), "调用方返回时枚举仍须被阻塞");
        } finally { release.countDown(); }
        await(finished);
    }

    @Test void stalledPipeCloseCannotDelayCallerDirectTerminationOrOtherPipes() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var otherPipesClosed = new CountDownLatch(2);
        var process = new FakeProcess() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) {}
                    @Override public void close() {
                        entered.countDown();
                        try { hold(release); } finally { finished.countDown(); }
                    }
                };
            }
            @Override public InputStream getInputStream() { return countedClose(otherPipesClosed); }
            @Override public InputStream getErrorStream() { return countedClose(otherPipesClosed); }
        };
        try {
            runBounded(() -> CommandProcessCleanup.stop(process));
            await(entered);
            await(process.destroyed);
            await(otherPipesClosed);
            assertEquals(1, finished.getCount(), "阻塞的管道不能延迟另一个管道的关闭");
        } finally { release.countDown(); }
        await(finished);
    }

    @Test void preservesInterruptedCallerAndIgnoresCleanupProviderFailures() throws Exception {
        var closed = new CountDownLatch(2);
        var process = new FakeProcess() {
            @Override public Stream<ProcessHandle> descendants() { throw new UnsupportedOperationException("unavailable"); }
            @Override public OutputStream getOutputStream() { throw new IllegalStateException("unavailable"); }
            @Override public InputStream getInputStream() { return countedClose(closed); }
            @Override public InputStream getErrorStream() { return countedClose(closed); }
        };
        Thread.currentThread().interrupt();
        try {
            CommandProcessCleanup.stop(process);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        await(process.destroyed);
        await(closed);
    }

    @Test void closePipesDoesNotTerminateSuccessfulProcess() throws Exception {
        var closed = new CountDownLatch(2);
        var process = new FakeProcess() {
            @Override public InputStream getInputStream() { return countedClose(closed); }
            @Override public InputStream getErrorStream() { return countedClose(closed); }
        };
        CommandProcessCleanup.closePipes(process);
        await(closed);
        assertEquals(1, process.destroyed.getCount());
    }

    @Test void stalledDirectDestructionCannotBlockCallerOrPipeCleanup() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var closed = new CountDownLatch(2);
        var process = new FakeProcess() {
            @Override public Process destroyForcibly() {
                entered.countDown();
                try { hold(release); return this; }
                finally { finished.countDown(); }
            }
            @Override public InputStream getInputStream() { return countedClose(closed); }
            @Override public InputStream getErrorStream() { return countedClose(closed); }
        };
        try {
            runBounded(() -> CommandProcessCleanup.stop(process));
            await(entered);
            await(closed);
            assertEquals(1, finished.getCount());
        } finally { release.countDown(); }
        await(finished);
    }

    private static InputStream countedClose(CountDownLatch closed) {
        return new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() { closed.countDown(); }
        };
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try { latch.await(); return; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "等待模拟清理协调超时");
    }
    private static void runBounded(Runnable operation) throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(operation, null);
        Thread.ofPlatform().daemon(true).name("cleanup-test-caller").start(task);
        task.get(5, TimeUnit.SECONDS);
    }
    private static class FakeProcess extends Process {
        final CountDownLatch destroyed = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { throw new AssertionError("清理不能在调用方线程执行 waitFor"); }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed.countDown(); }
        @Override public Process destroyForcibly() { destroyed.countDown(); return this; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
