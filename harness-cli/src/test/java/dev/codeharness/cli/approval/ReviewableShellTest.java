package dev.codeharness.cli.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewableShellTest {
    @Test
    void acceptsOnlyExplicitReadOnlySegmentsInABoundedChain() {
        String command = "git log --oneline -5 && echo --- && git diff --stat | tail -20"
                + " && echo --- && git status --short | wc -l";

        var plan = ReviewableShell.parse(command);

        assertNotNull(plan);
        assertEquals(7, plan.segments().size());
        assertEquals(List.of("&&", "&&", "|", "&&", "&&", "|"), plan.operators());
    }

    @Test
    void acceptsUnconditionalReadOnlySegmentsAndDiscardedStandardError() {
        var plan = ReviewableShell.parse(
                "ls -1d */ 2>/dev/null; echo ---; git status --short | head -30; echo ---; git log --oneline -5");

        assertNotNull(plan);
        assertEquals(6, plan.segments().size());
        assertEquals(List.of(";", ";", "|", ";", ";"), plan.operators());
        assertTrue(plan.segments().getFirst().discardStandardError());
        assertEquals(List.of("ls", "-1d", "*/"), plan.segments().getFirst().words());
    }

    @Test
    void acceptsQuotedEchoAndMultipleRgRootsInALongReadOnlyChain() {
        var plan = ReviewableShell.parse("git status --short; echo '---'; git log --oneline -5; echo '---';"
                + " rg --files soulclaw-knowledge-server/src/main/java soulclaw-knowledge-client/src/main/java"
                + " soulclaw-knowledge-infra/src/main/java | head -200");

        assertNotNull(plan);
        assertEquals(6, plan.segments().size());
        assertEquals(List.of(";", ";", ";", ";", "|"), plan.operators());
        assertEquals(List.of("echo", "---"), plan.segments().get(1).words());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git status && rm -rf .",
            "git status || echo fallback",
            "git status > output.txt",
            "git status && echo $PATH",
            "git log --oneline -5 && whoami",
            "git status | tail 20",
            "git status | wc -c",
            "git status ; echo split ; rm -rf .",
            "ls -1d ../*/ 2>/dev/null",
            "ls -1d */ 2>output.txt",
            "git status 1>/dev/null",
            "git status \u0026 echo split"
    })
    void rejectsUnknownMutationExpansionRedirectionAndFilterForms(String command) {
        assertNull(ReviewableShell.parse(command));
    }

    @Test
    void unknownButStructurallyReadableCommandCanReachClassifier() {
        assertTrue(ReviewableShell.reviewCandidate("git show --stat HEAD"));
        assertTrue(ReviewableShell.reviewCandidate("grep -n controller src"));
        assertFalse(ReviewableShell.reviewCandidate("rm -rf ."));
        assertFalse(ReviewableShell.reviewCandidate("curl https://example.com/upload"));
        assertFalse(ReviewableShell.reviewCandidate("sed -i s#old#new# src/File.java"));
        assertFalse(ReviewableShell.reviewCandidate("git push origin main"));
        assertFalse(ReviewableShell.reviewCandidate("rg --pre ./helper pattern ."));
    }
}
