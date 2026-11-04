package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 经真实 CLI 调用验证多目录文件发现、正文搜索及受限 head，不请求真实模型。 */
class SearchApprovalCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVER_MAIN = "soulclaw-knowledge-server/src/main/java";
    private static final String SERVER_TEST = "soulclaw-knowledge-server/src/test/java";
    private static final String CLIENT_MAIN = "soulclaw-knowledge-client/src/main/java";
    private static final String INFRA_MAIN = "soulclaw-knowledge-infra/src/main/java";
    private static final String FILES_COMMAND = "rg --files " + SERVER_MAIN + " " + SERVER_TEST + " "
            + CLIENT_MAIN + " " + INFRA_MAIN + " | head -200";
    private static final String SEARCH_COMMAND = "rg -n \"@RestController|@Service|@Repository|@Scheduled|@XxlJob|"
            + "CompletableFuture|ThreadPoolTaskExecutor|Redis|Elasticsearch|RestClient|S3|Minio\" "
            + SERVER_MAIN + " " + INFRA_MAIN + " | head -240";

    @TempDir Path temporary;

    @Test void 原始四目录文件发现直接本地批准且不读取文件正文() throws Exception {
        Path root = workspace();
        var expected = new ArrayList<String>();
        for (String directory : List.of(SERVER_MAIN, SERVER_TEST, CLIENT_MAIN, INFRA_MAIN)) {
            for (String file : List.of("A.java", "B.java")) {
                String path = directory + "/" + file;
                expected.add(path);
                write(root, path, "FILE_CONTENT_MUST_NOT_APPEAR");
            }
        }
        write(root, "unselected/Other.java", "UNSELECTED_CONTENT_MUST_NOT_APPEAR");
        write(root, SERVER_MAIN + "/.git/Internal.java", "INTERNAL_CONTENT_MUST_NOT_APPEAR");
        write(root, SERVER_MAIN + "/.harness/Session.java", "INTERNAL_CONTENT_MUST_NOT_APPEAR");

        Result result = run(root, FILES_COMMAND, "review", noApprovalInput());

        assertLocallyApproved(result);
        List<String> paths = result.tool().lines().filter(line -> line.endsWith(".java")).toList();
        assertEquals(expected.size(), paths.size(), result.tool());
        assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(paths), result.tool());
        assertFalse(result.tool().contains("CONTENT_MUST_NOT_APPEAR"), result.tool());
    }

    @Test void 原始正则竖线是搜索模式且保留路径与真实行号() throws Exception {
        Path root = workspace();
        write(root, SERVER_MAIN + "/Api.java", "package fixture;\n\n@RestController\n@Service\nRedis cache;\n");
        write(root, INFRA_MAIN + "/Store.java", "package fixture;\nMinio client;\nS3 store;\n");
        write(root, "unselected/Other.java", "@Service OUTSIDE_SELECTED_ROOT\n");

        Result result = run(root, SEARCH_COMMAND, "review", noApprovalInput());

        assertLocallyApproved(result);
        List<String> lines = searchLines(result.tool());
        assertEquals(5, lines.size(), result.tool());
        assertTrue(lines.containsAll(List.of(SERVER_MAIN + "/Api.java:3: @RestController",
                SERVER_MAIN + "/Api.java:4: @Service", SERVER_MAIN + "/Api.java:5: Redis cache;",
                INFRA_MAIN + "/Store.java:2: Minio client;", INFRA_MAIN + "/Store.java:3: S3 store;")), result.tool());
        assertFalse(result.tool().contains("OUTSIDE_SELECTED_ROOT"), result.tool());
    }

    @Test void 双引号与单引号目录中的空格不会被拆成多个路径() throws Exception {
        Path root = workspace();
        write(root, "a source/First.java", "package fixture;\n@Service\n");
        write(root, "b source/Second.java", "Redis cache;\n");
        write(root, "a/Unrelated.java", "@Service DO_NOT_READ\n");

        Result result = run(root, "rg -n \"@Service|Redis\" \"a source\" 'b source' | head -n 10",
                "review", noApprovalInput());

        assertLocallyApproved(result);
        assertEquals(List.of("a source/First.java:2: @Service", "b source/Second.java:1: Redis cache;"),
                searchLines(result.tool()), result.tool());
    }

    @Test void 多目录文件名head只计算结果行并精确保留前二百条() throws Exception {
        Path root = workspace();
        var allPaths = new ArrayList<String>();
        for (String directory : List.of("a", "b")) {
            for (int index = 0; index < 105; index++) {
                String path = directory + "/File" + String.format(java.util.Locale.ROOT, "%03d", index) + ".java";
                allPaths.add(path);
                write(root, path, "不应读取的文件正文");
            }
        }

        Result result = run(root, "rg --files a b | head -200", "review", noApprovalInput());

        assertLocallyApproved(result);
        assertEquals(allPaths.subList(0, 200), result.tool().lines().filter(line -> line.endsWith(".java")).toList(), result.tool());
        assertTrue(result.tool().contains("head"), "达到 head 上限应给出说明：" + result.tool());
    }

    @Test void 正文head只计算匹配行且精确保留前二百四十条() throws Exception {
        Path root = workspace();
        var contents = new StringBuilder();
        var expected = new ArrayList<String>();
        for (int index = 1; index <= 250; index++) {
            String body = index % 2 == 0 ? "Redis r;" : "@Service";
            contents.append(body).append('\n');
            if (index <= 240) expected.add("src/A.java:" + index + ": " + body);
        }
        write(root, "src/A.java", contents.toString());

        Result result = run(root, "rg -n \"@Service|Redis\" src | head -240", "review", noApprovalInput());

        assertLocallyApproved(result);
        assertEquals(expected, searchLines(result.tool()), result.tool());
        assertTrue(result.tool().contains("head"), "达到 head 上限应给出说明：" + result.tool());
    }

    @Test void 手动模式仍由用户逐项确认并且拒绝后不搜索() throws Exception {
        Path root = workspace();
        write(root, "src/A.java", "@Service SHOULD_NOT_BE_SEARCHED\n");
        AtomicInteger confirmations = new AtomicInteger();
        TerminalInput input = new TerminalInput() {
            public String readLine() { confirmations.incrementAndGet(); return "n"; }
            public boolean isTerminal() { return true; }
        };

        Result result = run(root, "rg -n \"@Service\" src | head -10", "manual", input);

        assertCompleted(result);
        assertEquals(1, confirmations.get());
        assertTrue(result.err().contains("[y/N]"), result.err());
        assertTrue(result.tool().contains("命令未获批准"), result.tool());
        assertFalse(result.tool().contains("SHOULD_NOT_BE_SEARCHED"), result.tool());
        assertEquals(0, result.review().path("localApprovals").asInt());
        assertEquals(0, result.review().path("requests").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rg --files ../outside | head -200",
            "rg -n \"@Service\" ../outside | head -240",
            "rg -n \"@Service\" src > blocked.txt",
            "rg --files src | head -200 > blocked.txt",
            "rg -n \"@Service\" src | head -240 & echo unexpected > blocked.txt",
            "rg --files src | head -0",
            "rg --files src | head -1001"
    })
    void 越界重定向串接和无效head不能取得本地权限(String command) throws Exception {
        Path root = workspace();
        write(root, "src/A.java", "@Service\n");
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        String sentinel = "OUTSIDE_WORKSPACE_CONTENT_MUST_NOT_APPEAR";
        Files.writeString(outside.resolve("Outside.java"), "@Service " + sentinel + "\n", StandardCharsets.UTF_8);

        Result result = run(root, command, "review", noHeadlessInput());

        assertCompleted(result);
        assertTrue(result.tool().contains("命令未获批准"), result.tool());
        assertFalse(result.tool().contains(sentinel), result.tool());
        assertFalse(result.out().contains(sentinel), result.out());
        assertFalse(Files.exists(root.resolve("blocked.txt")));
        assertEquals(0, result.review().path("localApprovals").asInt());
        assertEquals(0, result.review().path("requests").asInt());
        assertEquals(1, result.review().path("manual").asInt());
    }

    private Path workspace() throws IOException {
        return Files.createDirectories(temporary.resolve("workspace")).toRealPath();
    }

    private static void write(Path workspace, String relative, String text) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static TerminalInput noApprovalInput() {
        return new TerminalInput() {
            public String readLine() { throw new AssertionError("原生搜索不得要求终端审批"); }
            public boolean isTerminal() { return true; }
        };
    }

    private static TerminalInput noHeadlessInput() {
        return new TerminalInput() {
            public String readLine() { throw new AssertionError("无交互终端不得读取审批输入"); }
            public boolean isTerminal() { return false; }
        };
    }

    private Result run(Path root, String command, String mode, TerminalInput input) throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var errors = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                requests.add(request);
                if (request.path("tools").isEmpty()) throw new AssertionError("不应请求审核模型");
                if (requests.size() > 2) throw new AssertionError("不应追加模型请求");
                Map<String, Object> message = requests.size() == 1 ? Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(Map.of("id", "search-1", "type", "function", "function",
                                Map.of("name", "run_command", "arguments", JSON.writeValueAsString(Map.of("command", command))))))
                        : Map.of("role", "assistant", "content", "已核对搜索结果");
                reply(exchange, message);
            } catch (Exception | AssertionError failure) {
                errors.add(failure.toString());
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            // 测试自建配置放在查询工作区之外，避免正文测试把配置内容作为普通文件读取。
            Path config = temporary.resolve("search-fixture.yaml");
            Files.writeString(config, "provider: openai-compatible\nmodel: search-fixture\napi-key: fixture-only-key\n"
                    + "base-url: http://127.0.0.1:" + server.getAddress().getPort() + "/v1\n"
                    + "web:\n  enabled: false\npermissions:\n  mode: " + mode
                    + "\n  reviewer:\n    provider: missing-review-provider\n", StandardCharsets.UTF_8);
            var cli = new CommandLine(new HarnessCli(input));
            StringWriter out = new StringWriter(), err = new StringWriter();
            cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
            int code = cli.execute("--workspace", root.toString(), "--config", config.toString(),
                    "--no-stream", "--no-color", "--quiet", "--json", "run", "查询工作区文件和代码");
            return new Result(code, out.toString(), err.toString(), List.copyOf(requests), List.copyOf(errors));
        } finally { server.stop(0); }
    }

    private static void reply(HttpExchange exchange, Map<String, Object> message) throws IOException {
        byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message,
                "finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop")),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 4)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static List<String> searchLines(String output) {
        return output.lines().filter(line -> line.matches(".*:[0-9]+:.*")).toList();
    }

    private static void assertCompleted(Result result) throws Exception {
        assertEquals(0, result.code(), result.err());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(2, result.requests().size());
        JsonNode json = JSON.readTree(result.out());
        assertEquals("COMPLETED", json.path("status").asText(), result.out());
        assertEquals(2, json.path("iterations").asInt(), result.out());
    }

    private static void assertLocallyApproved(Result result) throws Exception {
        assertCompleted(result);
        assertTrue(result.tool().startsWith("退出码：0\n"), result.tool());
        assertEquals(0, result.review().path("requests").asInt(), result.out());
        assertEquals(0, result.review().path("manual").asInt(), result.out());
        assertEquals(1, result.review().path("localApprovals").asInt(), result.out());
        assertFalse(result.err().contains("[y/N]"), result.err());
    }

    private record Result(int code, String out, String err, List<JsonNode> requests, List<String> errors) {
        JsonNode review() throws IOException { return JSON.readTree(out).path("review"); }

        String tool() {
            for (JsonNode message : requests.getLast().path("messages")) {
                if (message.path("role").asText().equals("tool")) return message.path("content").asText();
            }
            throw new AssertionError("模型没有收到搜索工具结果");
        }
    }
}
