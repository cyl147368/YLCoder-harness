package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class HarnessCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void helpAndProvidersWorkWithoutCredentials() {
        Result help = execute("--help");
        assertEquals(0, help.code);
        assertTrue(help.out.contains("resume"));
        assertTrue(help.out.contains("--allow-commands"));
        assertTrue(help.out.contains("--no-auto-review"));
        assertTrue(help.out.contains("--token-counter"));
        assertTrue(help.out.contains("--parallel-reads"));
        assertTrue(help.out.contains("--no-stream"));
        Result providers = execute("providers");
        assertEquals(0, providers.code);
        assertTrue(providers.out.contains("anthropic"));
        assertTrue(providers.out.contains("openai-compatible"));
    }

    @Test void confirmationDisplaysControlsAsTextInsteadOfExecutingTerminalEscapes() {
        assertEquals("cmd \\u001b[2J\\u000d\\u202e\n下一行", HarnessCli.confirmationText("cmd \u001b[2J\r\u202e\n下一行"));
    }

    @Test void realCliSearchesReadsWritesVerifiesAndResumes() throws Exception {
        Files.writeString(workspace.resolve("answer.txt"), "旧答案\n");
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            int number = requests.size();
            String tool = switch (number) { case 1 -> "search_files"; case 2 -> "read_file"; case 3 -> "write_file"; case 4 -> "run_command"; default -> null; };
            Map<String, Object> args = switch (number) {
                case 1 -> Map.of("query", "旧答案");
                case 2 -> Map.of("path", "answer.txt");
                case 3 -> Map.of("path", "answer.txt", "content", "新答案\n");
                case 4 -> Map.of("command", System.getProperty("os.name").toLowerCase().contains("win") ? "type answer.txt" : "cat answer.txt");
                default -> Map.of();
            };
            Map<String, Object> message = tool == null
                    ? Map.of("role", "assistant", "content", number == 5 ? "已更新并验证 answer.txt" : "已成功继续会话")
                    : Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call-" + number,
                    "type", "function", "function", Map.of("name", tool, "arguments", JSON.writeValueAsString(args)))));
            byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                    "finish_reason", tool == null ? "stop" : "tool_calls")), "usage", Map.of("prompt_tokens", 50, "completion_tokens", 10)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Result run = execute("--workspace", workspace.toString(), "--provider", "openai-compatible", "--base-url", url, "--api-key-env", "HARNESS_TEST_UNUSED_KEY",
                    "--model", "fake-model", "--allow-commands", "--parallel-reads", "4", "--quiet", "--json", "run", "将旧答案更新为新答案并验证");
            assertEquals(0, run.code, run.err);
            JsonNode result = JSON.readTree(run.out);
            assertEquals("COMPLETED", result.path("status").asText());
            assertEquals(5, result.path("iterations").asInt());
            assertEquals(250, result.path("usage").path("inputTokens").asInt());
            assertEquals("新答案\n", Files.readString(workspace.resolve("answer.txt")));
            assertTrue(requests.get(4).toString().contains("退出码：0"));
            assertTrue(requests.get(4).toString().contains("新答案"));
            String id = result.path("session").asText();
            SessionStore.Session session = new SessionStore(workspace).load(id);
            assertEquals("COMPLETED", session.status());
            assertEquals(4, session.transcript().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).count());
            Result resume = execute("resume", id, "检查变更是否保留", "--workspace", workspace.toString(),
                    "--base-url", url, "--api-key-env", "HARNESS_TEST_UNUSED_KEY", "--parallel-reads", "8", "--json", "--quiet");
            assertEquals(0, resume.code, resume.err);
            assertEquals("已成功继续会话", JSON.readTree(resume.out).path("answer").asText());
            JsonNode resumedMessages = requests.getLast().path("messages");
            assertTrue(resumedMessages.toString().contains("将旧答案更新为新答案"));
            assertEquals("检查变更是否保留", resumedMessages.get(resumedMessages.size() - 1).path("content").asText());
        } finally { server.stop(0); }
    }

    @Test void explicitNoStreamUsesSynchronousRequestWithoutRetry() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            for (boolean disabled : List.of(false, true)) {
                var args = new ArrayList<>(List.of("--workspace", workspace.toString(), "--provider", "openai-compatible",
                        "--base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "--api-key-env", "HARNESS_TEST_UNUSED_KEY", "--quiet", "--json"));
                if (disabled) args.add("--no-stream");
                args.addAll(List.of("run", "task"));
                Result result = execute(args.toArray(String[]::new));
                assertEquals(0, result.code, result.err);
                assertEquals("done", JSON.readTree(result.out).path("answer").asText());
                assertEquals(!disabled, requests.getLast().path("stream").asBoolean(false));
            }
            assertEquals(2, requests.size(), "每个任务只请求一次；忽略流式模式的 JSON 响应不得触发重试");
        } finally { server.stop(0); }
    }

    @Test void sessionStoreRoundTripsAndRejectsTraversalAndConcurrentWriters() throws Exception {
        SessionStore store = new SessionStore(workspace);
        String id = store.newId();
        try (var lease = store.lock(id)) {
            store.save(id, "custom", "model", "RUNNING", List.of(ChatMessage.user("task")));
            assertEquals("task", store.load(id).transcript().getFirst().content());
            assertThrows(java.io.IOException.class, () -> store.lock(id));
        }
        try (var lease = store.lock(id)) { assertTrue(lease.lock().isValid()); }
        assertThrows(java.io.IOException.class, () -> store.load("../outside"));
    }

    @Test void explicitMissingConfigIsReportedBeforeProviderCall() {
        Result result = execute("--workspace", workspace.toString(), "--config", workspace.resolve("missing.yaml").toString(), "run", "fix");
        assertEquals(1, result.code);
        assertTrue(result.err.contains("配置文件不存在"));
    }

    @Test void invalidTokenizerCliOptionFailsBeforeProviderOrMcpLaunch() {
        Result result = execute("--workspace", workspace.toString(), "--token-counter", "private-secret", "run", "fix");
        assertEquals(1, result.code);
        assertTrue(result.err.contains("token-counter 必须是"));
        assertFalse(result.err.contains("private-secret"));
    }

    @Test void invalidParallelReadsRejectBeforeProviderConstructionOrMcpLaunch() throws Exception {
        Path config = workspace.resolve("parallel.yaml");
        Files.writeString(config, "provider: nonexistent-provider\nmcp:\n  servers:\n    fixture:\n      command: nonexistent-mcp-executable\n");
        for (String value : new String[]{"0", "9", "-1"}) {
            Result result = execute("--workspace", workspace.toString(), "--config", config.toString(),
                    "--parallel-reads", value, "run", "fix");
            assertEquals(1, result.code);
            assertTrue(result.err.contains("parallel-reads 必须在 1 到 8 之间"), result.err);
            assertFalse(result.err.contains("nonexistent-provider"));
            assertFalse(result.err.contains("nonexistent-mcp-executable"));
        }
        Files.writeString(config, "provider: nonexistent-provider\nagent:\n  parallel-reads: 9\n");
        Result invalidConfig = execute("--workspace", workspace.toString(), "--config", config.toString(), "run", "fix");
        assertEquals(1, invalidConfig.code);
        assertTrue(invalidConfig.err.contains("agent.parallel-reads 必须在 1 到 8 之间"));
    }

    private static Result execute(String... arguments) {
        CommandLine cli = HarnessCli.commandLine();
        StringWriter out = new StringWriter(), err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)); cli.setErr(new PrintWriter(err, true));
        int code = cli.execute(arguments);
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
