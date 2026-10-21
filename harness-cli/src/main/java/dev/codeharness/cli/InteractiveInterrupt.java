package dev.codeharness.cli;

/** 区分 Esc 的单轮取消和 Ctrl+C 的会话退出；检查点清理期间停止重复中断。 */
final class InteractiveInterrupt implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Runnable restore;
    private final Runnable restoreEscape;
    private volatile boolean requested;
    private volatile boolean taskCancelled;
    private boolean taskRunning;
    private boolean rewind;
    private long lastEscape;
    private boolean finishing;

    InteractiveInterrupt(TerminalInput input) {
        restore = input.onInterrupt(this::request);
        restoreEscape = input.onEscape(this::escape);
    }

    private synchronized void escape() {
        if (finishing) return;
        long now = System.nanoTime();
        if (lastEscape != 0 && now - lastEscape <= 500_000_000L) {
            rewind = true;
            lastEscape = 0;
        } else lastEscape = now;
        if (taskRunning && !taskCancelled) {
            taskCancelled = true;
            // 行编辑器内的 Esc 通过空行结束审批，无需中断它自身的终端清理。
            if (Thread.currentThread() != owner) owner.interrupt();
        }
    }

    synchronized void beginTask() { taskCancelled = false; taskRunning = true; lastEscape = 0; }
    synchronized void finishTask() {
        taskRunning = false;
        if ((requested || taskCancelled) && Thread.currentThread() == owner) Thread.interrupted();
    }
    boolean cancelled() { return requested || taskCancelled; }
    synchronized boolean takeRewind() { boolean value = rewind; rewind = false; return value; }

    private synchronized void request() {
        if (finishing || requested) return;
        requested = true;
        owner.interrupt();
    }

    boolean requested() { return requested; }

    synchronized void finish() {
        finishing = true;
        finishTask();
        // 中断标志会使 NIO 写入直接失败；取消状态保存在 requested 中。
        if (requested && Thread.currentThread() == owner) Thread.interrupted();
    }

    @Override public void close() {
        finish();
        restore.run();
        restoreEscape.run();
    }
}
