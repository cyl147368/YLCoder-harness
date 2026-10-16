package dev.codeharness.cli.tools;

import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceReadCapabilitiesTest {
    @TempDir Path workspace;

    @Test void onlyExplicitWorkspaceReadsOptIntoParallelExecution() throws Exception {
        var registry = WorkspaceTools.create(workspace, request -> true);
        Set<String> safe = new HashSet<>();
        for (ToolDefinition definition : registry.definitions()) {
            Tool tool = registry.find(definition.name()).orElseThrow();
            if (tool.isParallelSafeRead()) safe.add(definition.name());
        }
        assertEquals(Set.of("list_files", "search_files", "read_file"), safe);
        for (String name : Set.of("write_file", "edit_file", "changes", "run_command")) {
            assertFalse(registry.find(name).orElseThrow().isParallelSafeRead(), name);
        }
        Tool unclassified = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("custom", "Custom", Map.of("type", "object")); }
            @Override public String execute(String args) { return ""; }
        };
        assertFalse(unclassified.isParallelSafeRead());
    }

    @Test void safeReadsRejectAlreadyInterruptedExecutionWithoutClearingTheFlag() throws Exception {
        var registry = WorkspaceTools.create(workspace, request -> true);
        for (String name : Set.of("list_files", "search_files", "read_file")) {
            Tool tool = registry.find(name).orElseThrow();
            try {
                Thread.currentThread().interrupt();
                assertThrows(java.io.InterruptedIOException.class, () -> tool.execute("{}"), name);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }
}
