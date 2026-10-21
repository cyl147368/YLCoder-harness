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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** 用临时 Git 仓库与 localhost 完整复现检查、人工批准删除、保存以及继续对话。 */
class WorkspaceCleanupInteractionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void onlyActualDeletionAsksUserAndSessionRemainsUsableAfterClean() throws Exception {
        for (String tool : List.of("git", "rg")) {
            try {
                var process = new ProcessBuilder(tool, "--version").redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0, "本机没有可用的 " + tool);
            } catch (java.io.IOException missing) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "本机没有安装 " + tool);
            }
        }
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        git(workspace, "init", "--quiet");
        Files.writeString(workspace.resolve("AGENTS.md"), "只用于本地验收。\n");
        git(workspace, "add", "AGENTS.md");
        Path generated = Files.createDirectories(workspace.resolve("__pycache__")).resolve("fixture.pyc");
        Files.writeString(generated, "disposable fixture");
        List<String> readCommands = List.of("git status --short --branch --untracked-files=all",
                "rg --files --hidden -g AGENTS.md -g '!.git'", "git clean -nd");
        List<JsonNode> mainRequests = new CopyOnWriteArrayList<>(), reviews = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            boolean review = request.path("tools").isEmpty();
            (review ? reviews : mainRequests).add(request);
            Map<String, Object> message;
            if (review) message = Map.of("role", "assistant", "content", "{\"decision\":\"approve\"}");
            else if (mainRequests.size() == 1) {
                var calls = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < readCommands.size(); i++) calls.add(call("inspect-" + i, readCommands.get(i)));
                message = Map.of("role", "assistant", "content", "先检查清理范围。", "tool_calls", calls);
            } else if (mainRequests.size() == 2) message = Map.of("role", "assistant", "content", "清理已确认的未跟踪文件。",
                    "tool_calls", List.of(call("cleanup", "git clean -fd")));
            else message = Map.of("role", "assistant", "content", mainRequests.size() == 3 ? "清理完成。" : "会话可以继续。");
            byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                    "finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        Path config = temporary.resolve("offline.yaml");
        Files.writeString(config, "provider: openai-compatible\nmodel: cleanup-fixture\napi-key-env: ''\nbase-url: http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1\n");
        var lines = List.of("清理工作区未跟踪文件", "y", "继续对话", "/history all", "/exit").iterator();
        TerminalInput input = new TerminalInput() {
            public String readLine() { return lines.hasNext() ? lines.next() : null; }
            public boolean isTerminal() { return true; }
        };
        StringWriter out = new StringWriter(), err = new StringWriter();
        try {
            var cli = new CommandLine(new HarnessCli(input));
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            int code = cli.execute("--workspace", workspace.toString(), "--config", config.toString(),
                    "--no-color", "--no-stream", "--quiet");
            assertEquals(0, code, out + "\n" + err);
        } finally { server.stop(0); }
        assertEquals(3, reviews.size(), "三条只读检查都必须进入 AI 审核");
        assertEquals(readCommands, reviews.stream().map(request -> {
            try { return JSON.readTree(request.path("messages").get(1).path("content").asText()).path("command").asText(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }).toList());
        assertEquals(1, err.toString().split("允许执行命令吗", -1).length - 1, err.toString());
        assertTrue(err.toString().contains("git clean -fd"), err.toString());
        assertFalse(Files.exists(generated));
        assertEquals("只用于本地验收。\n", Files.readString(workspace.resolve("AGENTS.md")));
        assertEquals(4, mainRequests.size());
        for (JsonNode message : mainRequests.get(1).path("messages")) {
            if (!message.path("role").asText().equals("tool")) continue;
            assertTrue(message.path("content").asText().startsWith("退出码：0"), message.toString());
            assertFalse(message.path("content").asText().contains(".harness"), message.toString());
        }
        assertTrue(out.toString().contains("会话可以继续") && out.toString().contains("历史对话"), out.toString());
        SessionStore store = new SessionStore(workspace);
        var sessions = store.recent(20);
        assertEquals(1, sessions.size());
        var transcript = store.load(sessions.getFirst().id()).transcript();
        assertEquals(List.of("清理工作区未跟踪文件", "继续对话"), transcript.stream()
                .filter(m -> m.role() == dev.codeharness.core.model.ChatMessage.Role.USER).map(dev.codeharness.core.model.ChatMessage::content).toList());
        assertEquals(1, transcript.stream().filter(m -> m.toolCalls().stream().anyMatch(call -> call.id().equals("cleanup"))).count());
        try (var lease = store.lock(sessions.getFirst().id())) { assertTrue(lease.lock().isValid()); }
    }

    private static Map<String, Object> call(String id, String command) throws java.io.IOException {
        return Map.of("id", id, "type", "function", "function", Map.of("name", "run_command",
                "arguments", JSON.writeValueAsString(Map.of("command", command))));
    }

    private static void git(Path workspace, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "init.templateDir="));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
