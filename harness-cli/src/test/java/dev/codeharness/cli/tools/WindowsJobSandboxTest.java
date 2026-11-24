package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WindowsJobSandboxTest {
    @Test @EnabledOnOs(OS.WINDOWS)
    void closingJobTerminatesAssignedProcess() throws Exception {
        Process process = sleeper();
        try {
            WindowsJobSandbox job = WindowsJobSandbox.attach(process);
            assertTrue(job.active(), "Job Object was not created or assigned");
            job.close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "process survived job close");
            job.close();
        } finally { process.destroyForcibly(); }
    }

    @Test void nullProcessIsNoOp() {
        WindowsJobSandbox job = WindowsJobSandbox.attach(null);
        assertFalse(job.active());
        job.close();
    }

    private static Process sleeper() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        String classes = Path.of(WorkspaceCommandLifecycleTest.CommandFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Path pid = Path.of(System.getProperty("java.io.tmpdir"), "job-sandbox-" + System.nanoTime() + ".pid");
        return new ProcessBuilder(java.toString(), "-cp", classes,
                WorkspaceCommandLifecycleTest.CommandFixture.class.getName(), "sleep", pid.toString(), "unused")
                .redirectErrorStream(true).start();
    }
}
