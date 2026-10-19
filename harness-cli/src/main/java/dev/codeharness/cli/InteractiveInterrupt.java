package dev.codeharness.cli;

/** Ctrl+C 只发出一次取消请求；保存检查点和恢复终端期间不再中断线程。 */
final class InteractiveInterrupt implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Runnable restore;
    private volatile boolean requested;
    private boolean finishing;

    InteractiveInterrupt(TerminalInput input) { restore = input.onInterrupt(this::request); }

    private synchronized void request() {
        if (finishing || requested) return;
        requested = true;
        owner.interrupt();
    }

    boolean requested() { return requested; }

    synchronized void finish() {
        finishing = true;
        // 中断标志会使 NIO 写入直接失败；取消状态保存在 requested 中。
        if (requested && Thread.currentThread() == owner) Thread.interrupted();
    }

    @Override public void close() {
        finish();
        restore.run();
    }
}
