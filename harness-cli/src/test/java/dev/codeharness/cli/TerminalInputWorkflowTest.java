package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TerminalInputWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST = "执行一次无害命令";
    private static final String SECOND = "验证下一个任务保持独立";
    @TempDir Path workspace;

    @Test void automaticReviewNeverConsumesTheNextTerminalOrPipedTask() throws Exception {
        for (boolean terminal : List.of(true, false)) {
            FakeInput input = new FakeInput(terminal, FIRST, SECOND, "/exit");
            try (Provider fixture = new Provider(true, true)) {
                Outcome outcome = execute(input, fixture);
                assertEquals(0, outcome.code, outcome.err);
                assertEquals(3, input.reads);
                assertFalse(outcome.err.contains("[y/N]"));
                assertWorkflow(fixture, true);
                assertEquals(1, fixture.reviews.size());
                JsonNode review = fixture.reviews.getFirst();
                assertEquals(2, review.path("messages").size());
                assertTrue(review.path("tools").isMissingNode() || review.path("tools").isEmpty());
                assertTrue(review.path("max_tokens").asInt() <= 96);
                assertFalse(review.toString().contains(FIRST), "审核不接收主任务或聊天历史");
            }
        }
    }

    @Test void disablingReviewRestoresManualApprovalAndFullAllowStillOverrides() throws Exception {
        for (boolean fullAllow : List.of(false, true)) {
            FakeInput input = fullAllow ? new FakeInput(true, FIRST, SECOND, "/exit")
                    : new FakeInput(true, FIRST, "y", SECOND, "/exit");
            try (Provider fixture = new Provider(true, true)) {
                Outcome outcome = execute(input, fixture, fullAllow
                        ? new String[]{"--no-auto-review", "--allow-commands"} : new String[]{"--no-auto-review"});
                assertEquals(0, outcome.code, outcome.err);
                assertEquals(fullAllow ? 3 : 4, input.reads);
                assertEquals(!fullAllow, outcome.err.contains("[y/N]"));
                assertTrue(fixture.reviews.isEmpty());
                assertWorkflow(fixture, true);
            }
        }
    }

    @Test void reviewerManualDecisionFallsBackWithoutEatingTaskInput() throws Exception {
        for (boolean terminal : List.of(true, false)) {
            FakeInput input = terminal ? new FakeInput(true, FIRST, "n", SECOND, "/exit")
                    : new FakeInput(false, FIRST, SECOND, "/exit");
            try (Provider fixture = new Provider(true, false)) {
                Outcome outcome = execute(input, fixture);
                assertEquals(0, outcome.code, outcome.err);
                assertEquals(terminal ? 4 : 3, input.reads);
                assertEquals(terminal, outcome.err.contains("[y/N]"));
                assertEquals(1, fixture.reviews.size());
                assertWorkflow(fixture, false);
            }
        }
    }

    @Test void terminalApprovalAndTasksShareOneReaderInOrder() throws Exception {
        FakeInput input = new FakeInput(true, FIRST, "y", SECOND, "/exit");
        try (Provider fixture = new Provider()) {
            Outcome outcome = execute(input, fixture);
            assertEquals(0, outcome.code, outcome.err);
            assertEquals(4, input.reads);
            assertEquals(List.of("terminal-approved"), Files.readAllLines(workspace.resolve("approved.txt")));
            assertWorkflow(fixture, true);
        }
    }

    @Test void pipedInputDeniesApprovalWithoutConsumingTheNextTask() throws Exception {
        FakeInput input = new FakeInput(false, FIRST, SECOND, "/exit");
        try (Provider fixture = new Provider()) {
            Outcome outcome = execute(input, fixture);
            assertEquals(0, outcome.code, outcome.err);
            assertEquals(3, input.reads);
            assertFalse(Files.exists(workspace.resolve("approved.txt")));
            assertWorkflow(fixture, false);
        }
    }

    @Test void refusalIsConsumedAsApprovalNotAsANewTask() throws Exception {
        FakeInput input = new FakeInput(true, FIRST, "不是 yes", SECOND, "/exit");
        try (Provider fixture = new Provider()) {
            Outcome outcome = execute(input, fixture);
            assertEquals(0, outcome.code, outcome.err);
            assertEquals(4, input.reads);
            assertFalse(Files.exists(workspace.resolve("approved.txt")));
            assertWorkflow(fixture, false);
        }
    }

    @Test void approvalEofDeniesWithoutExecutingCommand() throws Exception {
        FakeInput input = new FakeInput(true, FIRST);
        try (Provider fixture = new Provider()) {
            Outcome outcome = execute(input, fixture);
            assertEquals(0, outcome.code, outcome.err);
            assertFalse(Files.exists(workspace.resolve("approved.txt")));
            assertEquals(2, fixture.requests.size());
            assertEquals(List.of(FIRST), userTexts(fixture.requests.getLast()));
            assertTrue(toolResults(fixture.requests.getLast()).getFirst().path("content").asText().contains("未获批准"));
            assertTrue(fixture.failures.isEmpty(), fixture.failures.toString());
        }
    }

    @Test void approvalReadFailureNeverExecutesTheCommand() throws Exception {
        FakeInput input = new FakeInput(true, FIRST);
        input.failAfter = 1;
        try (Provider fixture = new Provider()) {
            Outcome outcome = execute(input, fixture);
            assertEquals(1, outcome.code);
            assertFalse(Files.exists(workspace.resolve("approved.txt")));
            for (JsonNode request : fixture.requests) assertEquals(List.of(FIRST), userTexts(request));
            assertTrue(fixture.failures.isEmpty(), fixture.failures.toString());
        }
    }

    private void assertWorkflow(Provider fixture, boolean approved) {
        assertTrue(fixture.failures.isEmpty(), fixture.failures.toString());
        assertEquals(3, fixture.requests.size());
        assertEquals(List.of(FIRST), userTexts(fixture.requests.get(0)));
        assertEquals(List.of(FIRST), userTexts(fixture.requests.get(1)));
        assertEquals(List.of(FIRST, SECOND), userTexts(fixture.requests.get(2)));
        List<JsonNode> results = toolResults(fixture.requests.get(1));
        assertEquals(1, results.size());
        assertEquals("terminal-command", results.getFirst().path("tool_call_id").asText());
        String output = results.getFirst().path("content").asText();
        assertTrue(output.contains(approved ? "退出码：0" : "未获批准"), output);
        assertEquals(1, toolResults(fixture.requests.get(2)).size(), "第二个任务不能重复执行命令");
    }

    private Outcome execute(TerminalInput input, Provider fixture, String... additional) throws Exception {
        Path config = workspace.resolve("offline.yaml");
        Files.writeString(config, "{}\n");
        CommandLine cli = new CommandLine(new HarnessCli(input));
        StringWriter out = new StringWriter(), err = new StringWriter();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        cli.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println("错误：" + failure.getMessage());
            return 1;
        });
        var arguments = new ArrayList<>(List.of("--workspace", workspace.toString(), "--config", config.toString(),
                "--provider", "openai-compatible", "--model", "terminal-fixture", "--base-url", fixture.url(),
                "--api-key-env", "", "--no-stream", "--quiet"));
        arguments.addAll(List.of(additional));
        int code = cli.execute(arguments.toArray(String[]::new));
        return new Outcome(code, out.toString(), err.toString());
    }

    private static List<String> userTexts(JsonNode request) {
        List<String> texts = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            if (message.path("role").asText().equals("user")) texts.add(message.path("content").asText());
        }
        return texts;
    }

    private static List<JsonNode> toolResults(JsonNode request) {
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            if (message.path("role").asText().equals("tool")) results.add(message);
        }
        return results;
    }

    private record Outcome(int code, String out, String err) {}

    private static final class FakeInput implements TerminalInput {
        private final boolean terminal;
        private final List<String> lines;
        private int reads;
        private int failAfter = Integer.MAX_VALUE;
        FakeInput(boolean terminal, String... lines) { this.terminal = terminal; this.lines = List.of(lines); }
        @Override public String readLine() throws IOException {
            if (reads >= failAfter) throw new IOException("夹具终端读取失败");
            int index = reads++;
            return index < lines.size() ? lines.get(index) : null;
        }
        @Override public boolean isTerminal() { return terminal; }
    }

    private static final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<JsonNode> reviews = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        Provider() throws IOException { this(false, true); }
        Provider(boolean reviewMode, boolean approveReview) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    assertFalse(request.path("stream").asBoolean());
                    if (reviewMode && (!request.has("tools") || request.path("tools").isEmpty())) {
                        reviews.add(request);
                        byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                                "message", Map.of("role", "assistant", "content", approveReview
                                        ? "{\"decision\":\"approve\"}" : "{\"decision\":\"manual\"}"), "finish_reason", "stop"))));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                        return;
                    }
                    requests.add(request);
                    int number = requests.size();
                    Map<String, Object> message;
                    if (number == 1) {
                        // 追加写入使意外重复执行表现为多行内容。
                        String safe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "dir /b" : "pwd";
                        String arguments = JSON.writeValueAsString(Map.of("command", reviewMode ? safe : "echo terminal-approved>> approved.txt"));
                        message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                                "id", "terminal-command", "type", "function", "function",
                                Map.of("name", "run_command", "arguments", arguments))));
                    } else {
                        assertTrue(number <= 3, "出现了额外的模型请求");
                        List<JsonNode> results = toolResults(request);
                        assertEquals(1, results.size(), "权限审批必须产生恰好一个结果");
                        String output = results.getFirst().path("content").asText();
                        assertTrue(output.contains("退出码：0") || output.contains("未获批准")
                                || output.contains("无法读取权限审批回答"), output);
                        message = Map.of("role", "assistant", "content", number == 2 ? "第一个任务已处理" : "第二个任务已处理");
                    }
                    byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                            "finish_reason", number == 1 ? "tool_calls" : "stop"))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (Throwable failure) {
                    failures.add(failure);
                    exchange.sendResponseHeaders(500, -1);
                } finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }
}
