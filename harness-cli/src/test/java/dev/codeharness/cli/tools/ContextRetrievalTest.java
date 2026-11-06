package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContextRetrievalTest {
    @TempDir Path temporary;
    private Path workspace() throws Exception { return Files.createDirectories(temporary.resolve("workspace")); }

    @Test void mapRanksMatchingDeclarationsExcludesRuntimeDirectoriesAndRefreshesEdits() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("Alpha.java"), "public class Alpha {}\n");
        Files.writeString(root.resolve("Foo.java"), "public record Billing(int value) {}\n");
        Files.createDirectories(root.resolve(".harness"));
        Files.writeString(root.resolve(".harness/Private.java"), "class BillingSecret {}\n");
        var map = new RepositoryMap(root);
        String result = map.execute("{\"query\":\"Billing\"}");
        assertTrue(result.contains("Foo.java — Billing:1"), result);
        assertFalse(result.contains("Alpha.java"));
        assertFalse(result.contains("BillingSecret"));
        Files.writeString(root.resolve("Foo.java"), "public class UpdatedLonger {}\n");
        assertFalse(map.execute("{\"query\":\"Billing\"}").contains("Foo.java"));
        assertTrue(map.execute("{\"query\":\"UpdatedLonger\"}").contains("UpdatedLonger:1"));
    }


    @Test void listSupportsExplicitContinuationAndInvalidArgumentsAreRejected() throws Exception {
        Path root = workspace();
        for (int index = 0; index < 5; index++) Files.writeString(root.resolve("file" + index + ".txt"), "value");
        var tool = WorkspaceTools.create(root, request -> false).find("list_files").orElseThrow();
        String first = tool.execute("{\"limit\":2}");
        assertTrue(first.contains("offset=2"));
        String second = tool.execute("{\"limit\":2,\"offset\":2}");
        assertTrue(second.contains("offset=4"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"offset\":-1}"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"limit\":101}"));
    }

    @Test void archiveEnabledReadsPassFullBodyToStoreBeforePreview() throws Exception {
        Path root = workspace();
        String body = "a".repeat(20000) + "MIDDLE_DETAIL" + "z".repeat(20000);
        Files.writeString(root.resolve("long.txt"), body);
        var observed = new java.util.concurrent.atomic.AtomicReference<String>();
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> CommandApproval.Decision.denied(),
                (id, output, limit) -> { observed.set(output); return "preview"; });
        assertEquals("preview", tools.find("read_file").orElseThrow().execute("{\"path\":\"long.txt\"}"));
        assertTrue(observed.get().contains("MIDDLE_DETAIL"));
        assertTrue(observed.get().endsWith("z".repeat(20000) + "\n"));
    }

    @Test void searchSupportsExplicitContinuation() throws Exception {
        Path root = workspace();
        var tools = WorkspaceTools.create(root, request -> false);
        Files.writeString(root.resolve("search.txt"), "needle 1\nneedle 2\nneedle 3\nneedle 4\nneedle 5\n");
        String first = tools.find("search_files").orElseThrow().execute("{\"query\":\"needle\",\"limit\":2}");
        assertTrue(first.contains("needle 1")); assertTrue(first.contains("needle 2")); assertFalse(first.contains("needle 3"));
        assertTrue(first.contains("offset=2"));
        String second = tools.find("search_files").orElseThrow().execute("{\"query\":\"needle\",\"limit\":2,\"offset\":2}");
        assertFalse(second.contains("needle 2")); assertTrue(second.contains("needle 3")); assertTrue(second.contains("needle 4"));
        assertTrue(second.contains("offset=4"));
    }
}
