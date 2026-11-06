package dev.codeharness.cli.trace;

import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 日志直接展示解析后的回复；协议包装、增量事件和任意厂商扩展不落盘。 */
class ProcessedResponseTraceTest {
    @TempDir Path workspace;

    private ExecutionTrace open() {
        return ExecutionTrace.open(workspace, "processed-response", 128, List.of("fixture-secret"), fail -> fail(fail));
    }

    private String read(ExecutionTrace trace) throws Exception {
        trace.close();
        return Files.readString(trace.path());
    }

    @Test void normalReplyKeepsTextToolsAndUsageWithoutRawEnvelope() throws Exception {
        var trace = open();
        trace.wireResponse(1, 1, 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"回复正文\"}}],\"vendor_extension\":\"UNWANTED_RAW\"}",
                false, "HTTP 正文读取结束");
        trace.response(1, new ChatResponse("回复正文", List.of(new ToolCall("call-1", "read_file", "{\"path\":\"pom.xml\"}")),
                new TokenUsage(17, 5, 9), FinishReason.TOOL_CALLS, Map.of()), 10);
        String log = read(trace);
        assertTrue(log.contains("HTTP 200") && log.contains("模型回复 #1"));
        assertTrue(log.contains("回复正文") && log.contains("工具：read\\_file") && log.contains("pom.xml"));
        assertTrue(log.contains("输入 17；输出 5；缓存输入 9"));
        assertFalse(log.contains("HTTP 响应报文") || log.contains("\"choices\"") || log.contains("UNWANTED_RAW"));
    }

    @Test void jsonErrorShowsUsefulFieldsOnlyAndRedactsSecrets() throws Exception {
        var trace = open();
        trace.wireResponse(1, 1, 400, "application/json",
                "{\"error\":{\"message\":\"模型不存在 fixture-secret\",\"type\":\"invalid_request\",\"code\":\"bad_model\",\"request_id\":\"req-42\",\"internal\":\"UNWANTED_RAW\"}}",
                false, "HTTP 正文读取结束");
        String log = read(trace);
        assertTrue(log.contains("厂商错误：模型不存在") && log.contains("错误类型：invalid\\_request"));
        assertTrue(log.contains("错误代码：bad\\_model") && log.contains("请求 ID：req-42"));
        assertFalse(log.contains("fixture-secret") || log.contains("UNWANTED_RAW") || log.contains("\"error\""));
    }

    @Test void multilineSseErrorKeepsNestedResponseReasonAndRequestId() throws Exception {
        var trace = open();
        trace.wireResponse(1, 1, 200, "text/event-stream",
                "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"UNWANTED_DELTA\"}\r\n\r\n"
                + "event: response.failed\r\ndata: {\"type\":\"response.failed\",\r\ndata: \"response\":{\"request_id\":\"req-stream\",\"error\":{\"message\":\"响应中断 fixture-secret\",\"code\":\"interrupted\"}}}\r\n\r\n",
                false, "响应读取未完成");
        trace.partial(1, "已收到的文字");
        String log = read(trace);
        assertTrue(log.contains("厂商错误：响应中断") && log.contains("请求 ID：req-stream"));
        assertTrue(log.contains("已收到的文字") && log.contains("未完成回复片段 #1"));
        assertFalse(log.contains("fixture-secret") || log.contains("UNWANTED_DELTA") || log.contains("data:") || log.contains("event:"));
        assertFalse(log.contains("## 模型回复 #1"));
    }

    @Test void arbitraryFailureBodyIsNotDumpedAndTraceCanContinue() throws Exception {
        var trace = open();
        trace.wireResponse(1, 1, 502, "text/html", "<html>UNWANTED_RAW fixture-secret</html>", true, "HTTP 正文读取结束");
        trace.failure(1, new IllegalStateException("厂商网关异常，HTTP 502"), 10);
        trace.response(2, ChatResponse.text("重试成功"), 10);
        String log = read(trace);
        assertTrue(log.contains("报文已截断") && log.contains("厂商网关异常，HTTP 502") && log.contains("重试成功"));
        assertFalse(log.contains("UNWANTED_RAW") || log.contains("fixture-secret") || log.contains("<html>"));
    }

    @Test void toolOnlyAndEmptyRepliesExplainAbsenceOfText() throws Exception {
        var trace = open();
        trace.response(1, new ChatResponse("", List.of(new ToolCall("call-1", "read_file", "{}")),
                new TokenUsage(0, 0, 0), FinishReason.TOOL_CALLS, Map.of()), 1);
        trace.response(2, ChatResponse.text(""), 1);
        String log = read(trace);
        assertTrue(log.contains("本次回复请求执行工具，没有文字正文。"));
        assertTrue(log.contains("厂商未返回可展示的正文。"));
        assertFalse(log.contains("（空）"));
    }

    @Test void errorFieldsCannotInjectMarkdownOrControlSequences() throws Exception {
        var trace = open();
        trace.wireResponse(1, 1, 400, "application/json",
                "{\"error\":{\"message\":\"bad\\n## 伪造标题<script>\\u001b[31m\"},\"request_id\":\"root-id\"}",
                false, "HTTP 正文读取结束");
        String log = read(trace);
        assertTrue(log.contains("请求 ID：root-id") && log.contains("&lt;script&gt;"));
        assertFalse(log.contains("\n## 伪造标题") || log.contains("\u001b"));
    }
}
