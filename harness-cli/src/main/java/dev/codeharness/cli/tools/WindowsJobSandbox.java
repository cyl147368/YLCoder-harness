package dev.codeharness.cli.tools;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Locale;

/**
 * 用 Windows Job Object 约束工作区命令的进程生命周期。Job 带 KILL_ON_JOB_CLOSE 标志，
 * 关闭时会终止 Job 内仍在运行的所有进程，包括已脱离直接子进程的后代。
 * 这里只管生命周期，不限制文件系统或网络访问；在加入 Job 之前已创建的子进程可能逃逸。
 */
final class WindowsJobSandbox implements AutoCloseable {
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int EXTENDED_LIMIT_SIZE_64 = 144;
    private static final int LIMIT_FLAGS_OFFSET_64 = 16;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final WindowsJobSandbox NONE = new WindowsJobSandbox(null);

    private Pointer job;

    private WindowsJobSandbox(Pointer job) { this.job = job; }

    /** 把进程加入新建的 Job；平台不支持或任一步骤失败时返回空操作实例。 */
    static WindowsJobSandbox attach(Process process) {
        if (process == null || !supported()) return NONE;
        try {
            Kernel kernel = Kernel.INSTANCE;
            Pointer job = kernel.CreateJobObjectW(null, null);
            if (job == null) return NONE;
            boolean keep = false;
            try (Memory limits = new Memory(EXTENDED_LIMIT_SIZE_64)) {
                limits.clear();
                limits.setInt(LIMIT_FLAGS_OFFSET_64, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
                if (!kernel.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits, EXTENDED_LIMIT_SIZE_64))
                    return NONE;
                Pointer handle = kernel.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, (int) process.pid());
                if (handle == null) return NONE;
                try {
                    keep = kernel.AssignProcessToJobObject(job, handle);
                } finally { kernel.CloseHandle(handle); }
                return keep ? new WindowsJobSandbox(job) : NONE;
            } finally {
                if (!keep) kernel.CloseHandle(job);
            }
        } catch (LinkageError | RuntimeException error) {
            return NONE;
        }
    }

    boolean active() { return job != null; }

    /** 终止 Job 内剩余的所有进程并释放句柄；重复调用无副作用。 */
    @Override public synchronized void close() {
        Pointer current = job;
        if (current == null) return;
        job = null;
        try {
            Kernel.INSTANCE.TerminateJobObject(current, 1);
        } catch (LinkageError | RuntimeException ignored) {
            // 下面关闭句柄仍会触发 KILL_ON_JOB_CLOSE。
        } finally {
            try { Kernel.INSTANCE.CloseHandle(current); } catch (LinkageError | RuntimeException ignored) { }
        }
    }

    private static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && Native.POINTER_SIZE == 8;
    }

    private interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class);
        Pointer CreateJobObjectW(Pointer security, WString name);
        boolean SetInformationJobObject(Pointer job, int informationClass, Pointer information, int length);
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean AssignProcessToJobObject(Pointer job, Pointer process);
        boolean TerminateJobObject(Pointer job, int exitCode);
        boolean CloseHandle(Pointer handle);
    }
}
