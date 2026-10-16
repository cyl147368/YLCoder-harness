package dev.codeharness.cli.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionBenchmarkTest {
    @Test void reportsComparableCorrectExecutionsWithoutAssumingSpeedup() throws Exception {
        Map<String, Object> report = ToolExecutionBenchmark.measure(5, 3);
        assertEquals(1, report.get("benchmarkVersion"));
        List<?> variants = (List<?>) report.get("variants");
        assertEquals(2, variants.size());
        for (Object rawVariant : variants) {
            Map<?, ?> variant = (Map<?, ?>) rawVariant;
            int limit = (Integer) variant.get("maxParallelReads");
            assertEquals(true, variant.get("allOutputChecksPassed"));
            List<?> samples = (List<?>) variant.get("samples");
            assertEquals(3, samples.size());
            for (Object rawSample : samples) {
                var sample = (ToolExecutionBenchmark.Sample) rawSample;
                assertEquals(1, sample.finalState());
                assertEquals(8, sample.readCompletions());
                assertEquals(2, sample.modelRequests());
                assertEquals(List.of(2, 12), sample.requestMessageCounts());
                assertEquals(List.of(2, 2), sample.requestSchemaCounts());
                assertTrue(sample.maxActiveReads() >= 1 && sample.maxActiveReads() <= limit);
                assertEquals(sample.maxActiveReads() > 1, sample.overlapObserved());
                assertTrue(sample.serializedRequestBytes() > 0);
                assertTrue(Double.isFinite(sample.elapsedMs()) && sample.elapsedMs() >= 0);
            }
        }
    }
}
