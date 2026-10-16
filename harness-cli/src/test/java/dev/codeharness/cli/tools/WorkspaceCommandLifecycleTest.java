package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 验证命令中断边界；不宣称提供操作系统级隔离。 */
class WorkspaceCommandLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void interruptionStopsCommandPromptlyPreservesFlagAndEventuallyStopsFixtureChild() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path directPid = workspace.resolve("fixture.pid"), childPid = workspace.resolve("fixture-child.pid");
        ToolRegistry registry = WorkspaceTools.create(workspace, ignored -> true, Duration.ofSeconds(30));
        String command = fixtureCommand("block", directPid, childPid);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().daemon(true).name("command-interruption-test").start(() -> {
            try {
                registry.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", command)));
                failure.set(new AssertionError("命令未中断便完成"));
            } catch (InterruptedException expected) {
                // WorkspaceTools 必须先恢复中断标记，再抛出中断异常。
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable unexpected) { failure.set(unexpected); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try {
            while (!Files.exists(directPid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directPid), "夹具未原子发布其 PID");
            while (!Files.exists(childPid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(childPid), "夹具子进程未原子发布其 PID");
            worker.interrupt();
            worker.join(4_000);
            assertFalse(worker.isAlive(), "命令工作线程未及时退出被中断的执行");
            assertNull(failure.get(), () -> "命令失败不符合预期：" + failure.get());
            assertTrue(interrupted.get(), "缺少 InterruptedException 或恢复后的中断标记");
            awaitStopped(readPid(directPid));
            awaitStopped(readPid(childPid));
        } finally {
            worker.interrupt();
            try { cleanupPid(childPid); }
            finally {
                try { cleanupPid(directPid); }
                finally {
                    worker.join(5_000);
                    assertFalse(worker.isAlive(), "命令工作线程未终止");
                }
            }
        }
    }

    @Test void commandReadsClosedStdinAndExitsNormally() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace-eof"));
        ToolRegistry registry = WorkspaceTools.create(workspace, ignored -> true, Duration.ofSeconds(5));
        String command = fixtureCommand("stdin-eof", workspace.resolve("eof.marker"), workspace.resolve("unused.pid"));
        String result = registry.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", command)));
        assertTrue(result.startsWith("退出码：0"), result);
        assertEquals("eof", Files.readString(workspace.resolve("eof.marker")), result);
    }

    private static long readPid(Path path) throws IOException { return Long.parseLong(Files.readString(path).trim()); }

    private static void cleanupPid(Path path) throws Exception {
        if (!Files.exists(path)) return;
        long pid = readPid(path);
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        awaitStopped(pid);
    }

    private static void awaitStopped(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "清理后进程仍存活：" + pid);
    }

    private static String fixtureCommand(String action, Path first, Path second) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                windows() ? "java.exe" : "java");
        String classes = Path.of(CommandFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String fixture = CommandFixture.class.getName();
        return shellQuote(java.toString()) + " -cp " + shellQuote(classes) + " " + shellQuote(fixture)
                + " " + shellQuote(action) + " " + shellQuote(first.toString()) + " " + shellQuote(second.toString());
    }

    private static String shellQuote(String value) {
        return windows() ? "\"" + value + "\"" : "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    public static final class CommandFixture {
        public static void main(String[] args) throws Exception {
            Path direct = Path.of(args[1]);
            if (args[0].equals("stdin-eof")) {
                if (System.in.read() < 0) Files.writeString(direct, "eof");
                return;
            }
            if (args[0].equals("sleep")) {
                atomicWrite(direct, Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30_000);
                return;
            }
            atomicWrite(direct, Long.toString(ProcessHandle.current().pid()));
            Path childPid = Path.of(args[2]);
            boolean onWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            Path java = Path.of(System.getProperty("java.home"), "bin", onWindows ? "java.exe" : "java");
            String classes = Path.of(CommandFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Process child = new ProcessBuilder(java.toString(), "-cp", classes, CommandFixture.class.getName(), "sleep", childPid.toString(), "unused")
                    .redirectErrorStream(true).start();
            child.waitFor();
        }

        private static void atomicWrite(Path target, String value) throws IOException {
            Path temp = Files.createTempFile(target.getParent(), ".fixture-pid-", ".tmp");
            try {
                Files.writeString(temp, value, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
    }

}
