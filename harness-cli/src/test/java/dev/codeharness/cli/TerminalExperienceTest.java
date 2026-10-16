package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TerminalExperienceTest {
    @TempDir Path workspace;

    @Test void localProjectStatusAndUnknownCommandsNeverSpendModelRequests() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "不得被本地概览读取的正文");
        Files.createDirectory(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve(".git"));
        Path config = workspace.resolve("offline.yaml");
        Files.writeString(config, "{}\n");
        AtomicInteger requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            var lines = List.of("/project", "/status", "/does-not-exist", "/help", "/exit").iterator();
            TerminalInput input = new TerminalInput() {
                public String readLine() { return lines.hasNext() ? lines.next() : null; }
                public boolean isTerminal() { return false; }
            };
            var cli = new CommandLine(new HarnessCli(input));
            StringWriter out = new StringWriter(), err = new StringWriter();
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            assertEquals(0, cli.execute("--config", config.toString(), "--workspace", workspace.toString(),
                    "--provider", "openai-compatible", "--model", "fixture", "--api-key-env", "",
                    "--base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "--no-color"));
            assertEquals(0, requests.get(), "本地命令不能消耗模型请求");
            assertTrue(out.toString().contains(workspace.toString()));
            assertTrue(out.toString().contains("README.md"));
            assertTrue(out.toString().contains("src/"));
            assertFalse(out.toString().contains("不得被本地概览读取的正文"));
            assertFalse(out.toString().contains(".git/"));
            assertTrue(out.toString().contains("未知命令"));
            assertTrue(out.toString().contains("尚未提交任务"));
            assertFalse(out.toString().contains("\033["));
            assertFalse(out.toString().contains("继续会话：ylc resume"));
            try (var sessions = Files.list(workspace.resolve(".harness/sessions"))) {
                assertTrue(sessions.noneMatch(path -> path.toString().endsWith(".json")));
            }
        } finally { server.stop(0); }
    }

    @Test void toolProgressDoesNotPrintCommandsContentsOrTerminalControls() throws Exception {
        StringWriter out = new StringWriter(), err = new StringWriter();
        var ui = new TerminalUi(new PrintWriter(out, true), new PrintWriter(err, true), false);
        var json = new ObjectMapper();
        ui.tool(new ToolCall("a", "write_file", json.writeValueAsString(Map.of("path", "src/\033[2J\n你好.java", "content", "private-content"))));
        ui.tool(new ToolCall("b", "run_command", json.writeValueAsString(Map.of("command", "echo private-command"))));
        assertTrue(err.toString().contains("写入文件"));
        assertTrue(err.toString().contains("执行命令"));
        assertFalse(err.toString().contains("\033"));
        assertFalse(err.toString().contains("private-content"));
        assertFalse(err.toString().contains("private-command"));
        assertTrue(err.toString().contains("执行进度"));
        assertTrue(err.toString().matches("(?s).*01  写入文件\\R      src/.*\\R  02  执行命令.*"));
    }

    @Test void responseWithoutToolEvidenceShowsReplyAndMissingUsageClearly() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        var ui = new TerminalUi(new PrintWriter(out, true), new PrintWriter(err, true), false);
        ui.summary("full-session-id", new AgentResult(AgentResult.Status.COMPLETED, "你好", "", 1,
                TokenUsage.ZERO, Duration.ofMillis(4716), List.of()), 0, true,
                new UsageDashboard.Snapshot(1, TokenUsage.ZERO, 0, 0, 0, null, null, "current-task"),
                UsageDashboard.Snapshot.empty("current-process"),
                new dev.codeharness.cli.approval.CommandReview.Stats(0, 0, 0, 0, TokenUsage.ZERO, 0));
        assertEquals("", err.toString(), "交互结束后只刷新底部状态，不能重复打印大面板");
        ui.prompt();
        assertTrue(out.toString().contains("已回复"));
        assertTrue(out.toString().contains("未调用工具"));
        assertTrue(out.toString().contains("未报告"));
        assertTrue(out.toString().contains("4.7 秒"));
        assertFalse(out.toString().contains("full-session-id"));
        assertTrue(out.toString().endsWith("ylc> "));
    }

    @Test void connectionAndUsageStayInFooterBelowAnswerInsteadOfRepeatedWelcomePanels() {
        StringWriter shared = new StringWriter();
        var writer = new PrintWriter(shared, true);
        var ui = new TerminalUi(writer, writer, false);
        ui.connection("openai-compatible", "footer-model", "example.test");
        ui.welcome(workspace, "openai-compatible", "footer-model", "session");
        assertFalse(shared.toString().contains("footer-model"), "模型状态不应占据欢迎区");
        ui.beginTask(false);
        ui.answer();
        writer.println("这是正文标记。");
        var usage = new UsageDashboard.Snapshot(1, new TokenUsage(100, 5, 50), 1, 1, 1, 50.0, 50.0, "current-task");
        ui.summary("session", new AgentResult(AgentResult.Status.COMPLETED, "这是正文标记。", "", 1,
                usage.total(), Duration.ofSeconds(1), List.of()), 0, true, usage, usage,
                new dev.codeharness.cli.approval.CommandReview.Stats(0, 0, 0, 0, TokenUsage.ZERO, 0));
        ui.prompt();
        String rendered = shared.toString();
        assertTrue(rendered.indexOf("这是正文标记。") < rendered.indexOf("footer-model"));
        assertTrue(rendered.indexOf("缓存命中率") < rendered.lastIndexOf("ylc> "));
        assertFalse(rendered.contains("本轮统计"));
        assertFalse(rendered.contains("进程累计"), "累计详情由 /status 主动展开");
    }

    @Test void zeroAndPartialUsageAreNotPresentedAsUnknownOrComplete() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        var ui = new TerminalUi(new PrintWriter(out, true), new PrintWriter(err, true), false);
        ui.connection("openai-compatible", "sample-model", "example.test");
        ui.status(workspace, "session", true,
                new UsageDashboard.Snapshot(2, new TokenUsage(200, 10, 10), 2, 2, 1, null, 50.0, "current-process"));
        assertTrue(out.toString().contains("50.0%（仅已报告部分）"));
        assertTrue(out.toString().contains("10（部分报告 1/2）"));
        assertTrue(out.toString().contains("恢复会话不包含历史计费用量"));
        out.getBuffer().setLength(0);
        ui.status(workspace, "session", true,
                new UsageDashboard.Snapshot(1, new TokenUsage(20, 0, 0), 1, 1, 1, 0.0, 0.0, "current-process"));
        assertTrue(out.toString().contains("0.0%（缓存输入 / 输入）"));
        assertFalse(out.toString().contains("未报告"));
    }
}
