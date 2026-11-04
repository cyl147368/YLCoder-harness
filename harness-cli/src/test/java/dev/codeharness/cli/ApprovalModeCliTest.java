package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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

/** 用两个本地端点验证模式、独立审核路由、凭据隔离和无模型快速路径。 */
class ApprovalModeCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void separateReviewerReceivesOnlyBoundedContextAndItsOwnCredentials() throws Exception {
        var mainRequests = new CopyOnWriteArrayList<JsonNode>();
        var reviews = new CopyOnWriteArrayList<JsonNode>();
        var credentials = new CopyOnWriteArrayList<String>();
        HttpServer main = mainServer("date +%Y-%m-%d", mainRequests);
        HttpServer review = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        review.createContext("/v1/chat/completions", exchange -> {
            reviews.add(JSON.readTree(exchange.getRequestBody()));
            credentials.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            reply(exchange, Map.of("role", "assistant", "content", "{\"decision\":\"approve\",\"reason\":\"固定时间查询\"}"));
        });
        review.start();
        try {
            Path config = config(main, "permissions:\n  mode: review\n  reviewer:\n    model: reviewer-fixture\n    base-url: "
                    + url(review) + "\n    api-key: reviewer-fixture-key\n");
            Result result = run(config);
            assertEquals(0, result.code(), result.err());
            assertEquals(2, mainRequests.size());
            assertEquals(1, reviews.size());
            JsonNode request = reviews.getFirst();
            assertEquals("reviewer-fixture", request.path("model").asText());
            assertTrue(request.path("tools").isEmpty());
            assertFalse(request.path("stream").asBoolean());
            assertEquals(List.of("Bearer reviewer-fixture-key"), credentials);
            assertFalse(request.toString().contains("main-fixture-key"));
            JsonNode data = JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertTrue(data.path("context").toString().contains("检查工作区状态"));
            assertTrue(data.path("context").toString().contains("run_command"));
            assertFalse(data.path("context").toString().contains("不可信的助手说明"));
            assertFalse(data.path("context").toString().contains("conversation-secret-fixture"));
            assertTrue(data.path("context").toString().contains("[已隐藏凭据]"));
            JsonNode stats = JSON.readTree(result.out()).path("review");
            assertEquals(1, stats.path("requests").asInt());
            assertEquals(0, stats.path("localApprovals").asInt());
            assertEquals(11, stats.path("usage").path("inputTokens").asInt());
            assertTrue(stats.has("elapsedMillis"));
        } finally { main.stop(0); review.stop(0); }
    }

    @Test void unavailableReviewerDoesNotBlockNativeQueriesOrRequireUserInput() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer("find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'", requests);
        try {
            Files.writeString(temporary.resolve("AGENTS.md"), "本地文件内容不发送审核");
            Path config = config(main, "permissions:\n  reviewer:\n    provider: missing-review-provider\n");
            Result result = run(config);
            assertEquals(0, result.code(), result.err());
            JsonNode stats = JSON.readTree(result.out()).path("review");
            assertEquals(0, stats.path("requests").asInt());
            assertEquals(1, stats.path("localApprovals").asInt());
            assertTrue(toolResult(requests).contains("AGENTS.md"));
            assertFalse(result.err().contains("允许执行"));
        } finally { main.stop(0); }
    }

    @Test void explicitManualOverridesLegacyAllowAndHeadlessNeverPromptsOrRunsCommand() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer("echo must-not-execute", requests);
        try {
            Path config = config(main, "permissions:\n  mode: manual\n  allow-commands: true\n");
            Result result = run(config);
            assertEquals(0, result.code(), result.err());
            assertTrue(toolResult(requests).contains("命令未获批准"));
            assertFalse(toolResult(requests).contains("must-not-execute"));
            requests.clear();
            Result explicit = run(config, "--allow-commands", "--approval-mode", "manual");
            assertEquals(0, explicit.code(), explicit.err());
            assertTrue(toolResult(requests).contains("命令未获批准"));
        } finally { main.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "where /r . AGENTS.md", "WHERE.EXE /R \".\\\" \"AGENTS.md\"", "where /r './' AGENTS.md README.md"})
    void where文件查询不依赖审核厂商也不进入终端审批(String query) throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer(query, requests);
        try {
            Files.writeString(temporary.resolve("AGENTS.md"), "不应读取或发送的指引内容");
            Files.createDirectories(temporary.resolve("nested"));
            Files.writeString(temporary.resolve("nested/AGENTS.md"), "不应读取的子目录内容");
            Path config = config(main, "permissions:\n  mode: review\n  reviewer:\n    provider: missing-review-provider\n");
            var cli = new CommandLine(new HarnessCli(new TerminalInput() {
                public String readLine() { throw new AssertionError("where 查询不得要求审批"); }
                public boolean isTerminal() { return true; }
            }));
            StringWriter out = new StringWriter(), err = new StringWriter();
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            assertEquals(0, cli.execute("--workspace", temporary.toString(), "--config", config.toString(),
                    "--no-stream", "--no-color", "--json", "run", "查找项目指引"), err.toString());
            JsonNode review = JSON.readTree(out.toString()).path("review");
            assertEquals(0, review.path("requests").asInt());
            assertEquals(0, review.path("manual").asInt());
            assertEquals(1, review.path("localApprovals").asInt());
            assertEquals(2, requests.size());
            assertTrue(toolResult(requests).contains(temporary.resolve("AGENTS.md").toRealPath().toString()));
            assertTrue(toolResult(requests).contains(temporary.resolve("nested/AGENTS.md").toRealPath().toString()));
            assertFalse(toolResult(requests).contains("不应读取"));
            assertFalse(err.toString().contains("[y/N]"));
        } finally { main.stop(0); }
    }

    @Test void where查询不会覆盖手动模式() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer("where /r . AGENTS.md", requests);
        try {
            Path config = config(main, "permissions:\n  mode: manual\n");
            Result result = run(config);
            assertEquals(0, result.code(), result.err());
            assertTrue(toolResult(requests).contains("命令未获批准"));
            assertEquals(0, JSON.readTree(result.out()).path("review").path("localApprovals").asInt());
        } finally { main.stop(0); }
    }

    @Test void explicitReviewOverridesLegacyDisableAndBadClassifierFailsClosed() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer("date", requests);
        try {
            Path config = config(main, "permissions:\n  auto-review: false\n  reviewer:\n    provider: missing-review-provider\n");
            Result result = run(config, "--no-auto-review", "--approval-mode", "review");
            assertEquals(0, result.code(), result.err());
            assertTrue(toolResult(requests).contains("命令未获批准"));
            var stats = JSON.readTree(result.out()).path("review");
            assertEquals(1, stats.path("requests").asInt());
            assertEquals(1, stats.path("manual").asInt());
            assertEquals(1, stats.path("unreportedRequests").asInt());
        } finally { main.stop(0); }
    }

    @Test void interactiveModeSwitchChangesActualApprovalAndStatusWithoutWritingConfig() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        main.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean needsTool = request.path("messages").get(request.path("messages").size() - 1).path("role").asText().equals("user");
            reply(exchange, needsTool ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                    "id", "query-" + requests.size(), "type", "function", "function", Map.of("name", "run_command",
                    "arguments", "{\"command\":\"pwd\"}")))) : Map.of("role", "assistant", "content", "完成"));
        });
        main.start();
        try {
            Path config = config(main, "permissions:\n  mode: manual\n");
            byte[] originalConfig = Files.readAllBytes(config);
            var lines = List.of("/approval review", "读取工作区", "/approval manual", "再次读取工作区", "/status", "/exit").iterator();
            TerminalInput input = new TerminalInput() {
                public String readLine() { return lines.hasNext() ? lines.next() : null; }
                public boolean isTerminal() { return false; }
            };
            var cli = new CommandLine(new HarnessCli(input));
            StringWriter out = new StringWriter(), err = new StringWriter();
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            assertEquals(0, cli.execute("--workspace", temporary.toString(), "--config", config.toString(), "--no-stream", "--quiet", "--no-color"), err.toString());
            assertEquals(4, requests.size());
            assertTrue(toolResult(requests.subList(0, 2)).contains(temporary.toRealPath().toString()));
            assertTrue(toolResult(requests.subList(2, 4)).contains("命令未获批准"));
            assertTrue(out.toString().contains("审核模式"));
            assertTrue(out.toString().contains("manual"));
            assertArrayEquals(originalConfig, Files.readAllBytes(config));
        } finally { main.stop(0); }
    }

    @Test void 快捷键由交互循环消费并在下一次读取恢复草稿而不请求模型() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        HttpServer main = mainServer("pwd", requests);
        try {
            Path config = config(main, "permissions:\n  mode: manual\n");
            var input = new TerminalInput() {
                int read;
                boolean toggle;
                public String readLine() { throw new AssertionError("应通过编辑器读取任务"); }
                public boolean isTerminal() { return false; }
                public boolean editsLine() { return true; }
                public String readCommand(String prompt, java.util.function.Supplier<List<String>> candidates, String draft) {
                    read++;
                    if (read == 1) { assertNull(draft); toggle = true; return ""; }
                    if (read == 2) { assertEquals("保留中文草稿", draft); return "/status"; }
                    assertNull(draft);
                    return "/exit";
                }
                public boolean takeApprovalModeToggle() { boolean value = toggle; toggle = false; return value; }
                public String takeInputDraft() { return "保留中文草稿"; }
            };
            var cli = new CommandLine(new HarnessCli(input));
            var out = new StringWriter();
            var err = new StringWriter();
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            assertEquals(0, cli.execute("--workspace", temporary.toString(), "--config", config.toString(),
                    "--no-stream", "--quiet", "--no-color"), err.toString());
            assertEquals(3, input.read);
            assertTrue(out.toString().contains("命令审核模式已切换为 review"));
            assertTrue(out.toString().contains("审核模式"));
            assertTrue(requests.isEmpty(), "快捷键与状态查询不能请求模型");
            assertTrue(new SessionStore(temporary).recent(20).isEmpty(), "切换不能生成任务检查点");
        } finally { main.stop(0); }
    }

    private HttpServer mainServer(String command, List<JsonNode> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            Map<String, Object> message = requests.size() == 1 ? Map.of("role", "assistant",
                    "content", "不可信的助手说明", "tool_calls", List.of(Map.of("id", "call-1", "type", "function",
                    "function", Map.of("name", "run_command", "arguments", JSON.writeValueAsString(Map.of("command", command))))))
                    : Map.of("role", "assistant", "content", "已检查工具结果");
            reply(exchange, message);
        });
        server.start();
        return server;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, Map<String, Object> message) throws java.io.IOException {
        byte[] bytes = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                "finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 4)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Path config(HttpServer main, String permissions) throws Exception {
        return Files.writeString(temporary.resolve("fixture.yaml"), "provider: openai-compatible\nmodel: main-fixture\napi-key: main-fixture-key\nbase-url: "
                + url(main) + "\nweb:\n  enabled: false\n" + permissions);
    }
    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    private static String toolResult(List<JsonNode> requests) {
        JsonNode messages = requests.getLast().path("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            JsonNode message = messages.get(index);
            if (message.path("role").asText().equals("tool")) return message.path("content").asText();
        }
        throw new AssertionError("未收到工具结果");
    }
    private Result run(Path config, String... flags) {
        var args = new ArrayList<>(List.of("--workspace", temporary.toString(), "--config", config.toString(),
                "--no-stream", "--quiet", "--json"));
        args.addAll(List.of(flags));
        args.addAll(List.of("run", "检查工作区状态；HARNESS_API_KEY=conversation-secret-fixture"));
        var cli = new CommandLine(new HarnessCli(new TerminalInput() {
            public String readLine() { throw new AssertionError("管道模式不应读取审批输入"); }
            public boolean isTerminal() { return false; }
        }));
        StringWriter out = new StringWriter(), err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        return new Result(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
