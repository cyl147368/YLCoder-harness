package dev.codeharness.cli.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.codeharness.core.agent.AgentRunOptions;
import org.junit.jupiter.api.Test;

class DelegateTaskTest {
    @Test
    void readOnlyChildOnlyGetsReadTools() {
        assertTrue(DelegateTask.childAllows(false, "read_file"));
        assertFalse(DelegateTask.childAllows(false, "write_file"));
        assertFalse(DelegateTask.childAllows(false, "run_command"));
        assertFalse(DelegateTask.childAllows(false, DelegateTask.NAME));
    }

    @Test
    void writableChildGetsWorkspaceToolsButCannotDelegate() {
        assertTrue(DelegateTask.childAllows(true, "edit_file"));
        assertTrue(DelegateTask.childAllows(true, "run_command"));
        assertFalse(DelegateTask.childAllows(true, DelegateTask.NAME));
        assertFalse(DelegateTask.childAllows(true, DelegateTask.WRITE_NAME));
    }

    @Test
    void onlyReadOnlyDelegationRunsInParallel() {
        var read = new DelegateTask(() -> null, AgentRunOptions::defaults, null);
        var write = DelegateTask.writable(() -> null, AgentRunOptions::defaults, null);
        assertTrue(read.isParallelSafeRead());
        assertFalse(write.isParallelSafeRead());
        assertEquals(DelegateTask.NAME, read.definition().name());
        assertEquals(DelegateTask.WRITE_NAME, write.definition().name());
    }
}
