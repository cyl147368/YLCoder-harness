package dev.codeharness.cli.trace;

import dev.codeharness.core.agent.ContextMetrics;
import dev.codeharness.core.model.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContextTraceTest {
    @TempDir Path workspace;
    @Test void summarySourceAndCompressionEvidenceAreRecordedSeparately() throws Exception {
        var trace = ExecutionTrace.open(workspace, "79589132-7e6d-46eb-bfc5-6e1ab791f4b1", 2000, List.of(), message -> fail(message));
        long id = trace.request("summarizer", "custom", "summary-model", false, 1,
                new ChatRequest(List.of(ChatMessage.user("历史参考")), List.of(), 500));
        trace.response(id, ChatResponse.text("结构摘要"), 12);
        trace.context(new ContextMetrics(12000, 1500, 5000, 20, false, false, 15, "已生成结构化摘要"));
        trace.finish("COMPLETED", null); trace.close();
        String text = Files.readString(trace.path());
        assertTrue(text.contains("来源：summarizer"), text);
        assertTrue(text.contains("整理前 12000；整理后 1500"));
        assertTrue(text.contains("摘要覆盖消息：20；复用：false；降级：false"));
    }
}
