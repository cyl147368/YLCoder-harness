package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.cli.tools.WorkspaceTools;
import dev.codeharness.core.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RipgrepReviewTest {
    @TempDir Path root;

    @Test void agentsDiscoveryUsesExternalExecutableAndIgnoresGitAndSessionInternals() throws Exception {
        String query = "rg --files --hidden -g AGENTS.md -g '!.git'";
        var request = new CommandApproval.Request(root.toRealPath(), query);
        var candidate = new ReadOnlyCommandGate().plan(request);
        assumeTrue(candidate.isPresent(), "本机没有可供审核使用的外部 rg 可执行文件");
        for (String name : List.of("rg.exe", "rg.cmd", "rg")) Files.writeString(root.resolve(name), "must not execute");
        for (String name : List.of("AGENTS.md", "nested/AGENTS.md", ".hidden/AGENTS.md", ".git/AGENTS.md", ".harness/AGENTS.md")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "private-instruction-contents");
        }
        var reviewer = new CommandReview(sent -> {
            assertFalse(sent.messages().toString().contains("private-instruction-contents"));
            return ChatResponse.text("{\"decision\":\"approve\"}");
        }, Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, pending -> {
            var result = reviewer.review(pending);
            assertTrue(result.approved(), result.reason());
            assertFalse(Path.of(result.arguments().getFirst()).startsWith(root));
            assertTrue(result.arguments().contains("--no-config"));
            return CommandApproval.Decision.direct(result.arguments());
        });
        String output = tools.find("run_command").orElseThrow().execute(new ObjectMapper().writeValueAsString(Map.of("command", query)));
        assertTrue(output.startsWith("退出码：0"), output);
        assertTrue(output.contains("AGENTS.md") && output.contains("nested") && output.contains(".hidden"), output);
        assertFalse(output.contains(".git"), output);
        assertFalse(output.contains(".harness"), output);
        assertFalse(output.contains("private-instruction-contents"), output);
        assertEquals(1, reviewer.stats().requests());
    }
}
