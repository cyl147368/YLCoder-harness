package dev.codeharness.cli.bench;

import dev.codeharness.core.agent.ContextCompactor;
import dev.codeharness.core.model.ChatMessage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextBenchmarkTest {
    @Test void tokenizerModesPreserveCurrentSourceWithinTheirMeasuredMessageBudgets() {
        for (String counterName : java.util.List.of("cl100k", "o200k")) {
            var counter = dev.codeharness.cli.TokenizerInputTokenCounter.create(counterName);
            for (String kind : java.util.List.of("code-history", "diagnostic-history", "multilingual-history")) {
                var fixture = ContextBenchmark.fixture(kind);
                var compacted = new ContextCompactor(counter).compact(fixture, 1_200, 1_000);
                ContextBenchmark.verifyRetention(fixture, compacted);
                assertTrue(counter.messageTokens(compacted) <= 1_200);
            }
        }
    }
    @Test void preservesCurrentCodeAndOpaqueMetadataInAllWorkloads() {
        for (String kind : java.util.List.of("code-history", "diagnostic-history", "multilingual-history")) {
            var fixture = ContextBenchmark.fixture(kind);
            var compacted = new ContextCompactor().compact(fixture, 1_200, 1_000);
            ContextBenchmark.verifyRetention(fixture, compacted);
            assertTrue(compacted.size() < fixture.size());
            assertEquals("opaque-signature-kept-exactly", compacted.get(compacted.size() - 2).toolCalls().getFirst().providerMetadata().get("fixture.signature"));
        }
    }

    @Test void rejectsLossOfInstructionsAndOrphanResults() {
        var fixture = ContextBenchmark.fixture("code-history");
        var compacted = new ArrayList<>(new ContextCompactor().compact(fixture, 1_200, 1_000));
        compacted.set(0, ChatMessage.system("不同指令"));
        assertThrows(IllegalStateException.class, () -> ContextBenchmark.verifyRetention(fixture, compacted));
        var orphan = new ArrayList<>(fixture);
        orphan.add(ChatMessage.toolResult(new dev.codeharness.core.model.ToolCall("missing", "read_file", "{}"), "unexpected"));
        assertThrows(IllegalStateException.class, () -> ContextBenchmark.verifyRetention(fixture, orphan));
    }
}
