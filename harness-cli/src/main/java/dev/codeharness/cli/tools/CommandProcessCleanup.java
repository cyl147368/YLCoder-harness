package dev.codeharness.cli.tools;

import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 对命令进程进行有时限的尽力清理。子孙进程发现和管道关闭在
 * 守护线程上运行，避免卡住的平台实现延长调用方的超时或中断处理。
 * 在给子孙进程发现留出有时限的先行机会后，独立尝试终止直接进程。
 */
final class CommandProcessCleanup {
    private CommandProcessCleanup() {}

    static void stop(Process process) {
        if (process == null) return;
        var descendantsFinished = new CountDownLatch(1);
        Thread.ofPlatform().daemon(true).name("harness-command-descendant-cleanup").start(() -> {
            try (var descendants = process.descendants()) {
                descendants.limit(1024).forEach(child -> {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                });
            } catch (RuntimeException ignored) { }
            finally { descendantsFinished.countDown(); }
        });
        Thread.ofPlatform().daemon(true).name("harness-command-process-cleanup").start(() -> {
            try { descendantsFinished.await(50, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        });
        closePipes(process);
    }

    static void closePipes(Process process) {
        if (process == null) return;
        closeAsync("stdin", process::getOutputStream);
        closeAsync("stdout", process::getInputStream);
        closeAsync("stderr", process::getErrorStream);
    }

    private static void closeAsync(String pipe, Supplier<? extends AutoCloseable> stream) {
        Thread.ofPlatform().daemon(true).name("harness-command-" + pipe + "-cleanup").start(() -> {
            try {
                AutoCloseable closeable = stream.get();
                if (closeable != null) closeable.close();
            } catch (Exception ignored) { }
        });
    }
}
