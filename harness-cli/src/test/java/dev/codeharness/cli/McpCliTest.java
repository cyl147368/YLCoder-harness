package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class McpCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void runPermissionIsSeparateFromShellAndResumeClosesEveryServer() throws Exception {
        Path config = writeConfig();
        AtomicInteger count = new AtomicInteger();
        AtomicReference<String> resultText = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            int number = count.incrementAndGet();
            Object message;
            if (number % 2 == 1) {
                String name = "";
                for (JsonNode tool : request.path("tools")) {
                    if (tool.path("function").path("name").asText().contains("echo_text")) {
                        name = tool.path("function").path("name").asText(); break;
                    }
                }
                message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "external-" + number,
                        "type", "function", "function", Map.of("name", name, "arguments", "{\"value\":\"hello\"}"))));
            } else {
                JsonNode messages = request.path("messages");
                resultText.set(messages.get(messages.size() - 1).path("content").asText());
                message = Map.of("role", "assistant", "content", "Complete");
            }
            byte[] body = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                    "finish_reason", number % 2 == 1 ? "tool_calls" : "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            List<String> common = List.of("--workspace", workspace.toString(), "--config", config.toString(),
                    "--provider", "openai-compatible", "--model", "fixture", "--base-url", url,
                    "--api-key-env", "HARNESS_TEST_UNUSED_KEY", "--quiet", "--json");
            List<String> denied = new ArrayList<>(common);
            denied.addAll(List.of("--allow-commands", "run", "使用外部 echo 工具"));
            Result first = execute(denied);
            assertEquals(0, first.code, first.err);
            assertTrue(resultText.get().contains("外部 MCP 工具权限被拒绝"));
            assertFalse(Files.readString(workspace.resolve("trace.txt")).contains("tools/call"));
            long initialPid = Long.parseLong(Files.readString(workspace.resolve("pid.txt")));
            awaitStopped(initialPid);

            List<String> approved = new ArrayList<>(common);
            approved.addAll(List.of("--allow-mcp-tools", "run", "使用外部 echo 工具"));
            Result second = execute(approved);
            assertEquals(0, second.code, second.err);
            assertTrue(resultText.get().contains("echo: hello"));
            String id = JSON.readTree(second.out).path("session").asText();
            long runPid = Long.parseLong(Files.readString(workspace.resolve("pid.txt")));
            awaitStopped(runPid);
            List<String> resume = new ArrayList<>(common);
            resume.addAll(List.of("--allow-mcp-tools", "resume", id, "再次回显"));
            Result resumed = execute(resume);
            assertEquals(0, resumed.code, resumed.err);
            assertTrue(resultText.get().contains("echo: hello"));
            long resumePid = Long.parseLong(Files.readString(workspace.resolve("pid.txt")));
            assertNotEquals(runPid, resumePid);
            awaitStopped(resumePid);
        } finally { server.stop(0); }
    }

    @Test void interactiveExitClosesConfiguredServerWithoutAnyModelRequest() throws Exception {
        Path config = writeConfig();
        java.io.InputStream oldInput = System.in;
        try {
            System.setIn(new ByteArrayInputStream("/exit\n".getBytes(StandardCharsets.UTF_8)));
            Result result = execute(List.of("--workspace", workspace.toString(), "--config", config.toString(),
                    "--provider", "ollama", "--base-url", "http://127.0.0.1:1/v1", "--quiet"));
            assertEquals(0, result.code, result.err);
            awaitStopped(Long.parseLong(Files.readString(workspace.resolve("pid.txt"))));
        } finally { System.setIn(oldInput); }
    }

    private Path writeConfig() throws Exception {
        Path config = workspace.resolve("trusted-config.yaml");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        // JSON 是合法 YAML，并能精确保留 Windows 路径和 classpath。
        Files.writeString(config, JSON.writeValueAsString(Map.of("mcp", Map.of("servers", Map.of("fixture",
                Map.of("command", executable, "args", List.of("-cp", System.getProperty("java.class.path"),
                        "dev.codeharness.cli.mcp.McpToolsTest$Fixture", "normal", workspace.resolve("pid.txt").toString(),
                        workspace.resolve("trace.txt").toString()), "timeout-seconds", 5))))));
        return config;
    }

    private static Result execute(List<String> args) {
        CommandLine cli = HarnessCli.commandLine();
        StringWriter out = new StringWriter(), err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)); cli.setErr(new PrintWriter(err, true));
        int code = cli.execute(args.toArray(String[]::new));
        return new Result(code, out.toString(), err.toString());
    }

    private static void awaitStopped(long pid) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private record Result(int code, String out, String err) { }
}
