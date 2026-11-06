package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.cli.tools.WorkspaceTools;
import dev.codeharness.core.model.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 真正经过 CLI、适配器和工作区工具的诊断记录；全部模型回复来自本机夹具。 */
class ExecutionTraceCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "trace-only-fixture-credential";
    private static final String ANSWER = "执行记录夹具的完整回复";
    @TempDir Path workspace;
    private HttpServer server;
    private Path config;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> serverErrors = new CopyOnWriteArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private String format;
    private BiFunction<JsonNode, Integer, Reply> replies;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        config = workspace.resolve("offline.yaml");
    }

    @AfterEach void stop() {
        server.stop(0);
        assertTrue(serverErrors.isEmpty(), serverErrors.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"chat", "responses"})
    void promptsToolsAndResultsAreReadableWithoutCredentialsOrRepeatedPayload(String selected) throws Exception {
        configure(selected, "trace:\n  tool-result-max-chars: 128\n");
        String fileText = "工具结果开头标记" + "x".repeat(2000) + "工具结果尾部标记";
        Files.writeString(workspace.resolve("sample.txt"), fileText);
        replies = (request, index) -> index == 1 ? tool("read-fixture", "read_file", "{\"path\":\"sample.txt\"}") : answer(ANSWER);
        Result run = runJson(false, "run", "请读取 sample.txt 并记录本次提示词");
        assertEquals(0, run.code, run.err);
        JsonNode report = JSON.readTree(run.out);
        String log = Files.readString(Path.of(report.path("traceFile").asText()));
        assertEquals(2, requests.size());
        assertEquals(2, count(log, "## 模型请求 #"));
        assertTrue(log.contains("read-fixture"), log);
        assertTrue(log.contains("sample.txt"), log);
        assertTrue(log.contains("工具开始") && log.contains("工具完成"), log);
        assertTrue(log.contains("工具结果开头标记"), log);
        assertTrue(log.contains("工具结果尾部标记"), "实际请求报文应保留完整工具结果");
        assertTrue(requests.getLast().toString().contains("工具结果尾部标记"), "工具预览限制不得缩短发给模型的结果");
        assertTrue(log.contains("已截断") && log.contains("new_content"), log);
        assertTrue(log.contains(ANSWER), log);
        assertTrue(log.contains("输入 17") && log.contains("缓存输入 9"), log);
        assertEquals(1, count(log, "请读取 sample.txt 并记录本次提示词"));
        assertFalse(log.contains(KEY), log);
        assertReferencesResolve(log);
        assertTrue(run.err.isBlank(), run.err);
        // 默认的项目搜索与列目录不会把诊断日志递归带回模型。
        var tools = WorkspaceTools.create(workspace, ignored -> false);
        assertFalse(tools.find("list_files").orElseThrow().execute("{}").contains(".harness"));
        assertFalse(tools.find("search_files").orElseThrow().execute("{\"query\":\"执行记录夹具的完整回复\"}").contains(".harness"));
    }

    @ParameterizedTest @ValueSource(strings = {"chat", "responses"})
    void allFallbackAttemptsAreLoggedAndSharePromptReferences(String selected) throws Exception {
        configure(selected, "");
        replies = (request, index) -> index < 4
                ? new Reply(400, "application/json", "{\"error\":{\"message\":\"stream unsupported fixture " + KEY + "\"}}")
                : answer(ANSWER);
        Result run = runJson(true, "run", "请验证递增重试记录");
        assertEquals(0, run.code, run.err);
        String log = Files.readString(Path.of(JSON.readTree(run.out).path("traceFile").asText()));
        assertEquals(4, requests.size());
        assertEquals(List.of(1000L, 2000L, 4000L), delays);
        assertEquals(4, count(log, "## 模型请求 #"));
        assertEquals(3, count(log, "## 模型请求失败 #"));
        assertEquals(1, count(log, "请验证递增重试记录"));
        assertTrue(log.contains("流式") && log.contains("非流式"), log);
        assertTrue(log.contains("HTTP 状态码 400"), log);
        assertFalse(log.contains(KEY), log);
        assertTrue(log.contains(ANSWER), log);
        assertReferencesResolve(log);
    }

    @Test void failedToolIsLoggedThenGivenToModelForRecovery() throws Exception {
        configure("chat", "");
        replies = (request, index) -> index == 1 ? tool("missing-read", "read_file", "{\"path\":\"not-here.txt\"}") : answer(ANSWER);
        Result run = runJson(false, "run", "读取不存在的文件");
        assertEquals(0, run.code, run.err);
        String log = Files.readString(Path.of(JSON.readTree(run.out).path("traceFile").asText()));
        assertTrue(log.contains("工具失败"), log);
        assertTrue(log.contains("missing-read") && log.contains("not-here.txt"), log);
        assertTrue(requests.getLast().toString().contains("not-here.txt"));
        assertTrue(log.contains(ANSWER), log);
    }

    @Test void partialStreamHasFailureAndDoesNotInventFinalReplyOrRetry() throws Exception {
        configure("chat", "");
        replies = (request, index) -> new Reply(200, "text/event-stream", "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"已显示的半段回复\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"error\":{\"message\":\"fixture stream failed\"}}\n\n");
        Result run = runJson(true, "run", "验证部分输出");
        assertEquals(1, run.code, run.err);
        String log = Files.readString(Path.of(JSON.readTree(run.out).path("traceFile").asText()));
        assertEquals(1, requests.size());
        assertTrue(delays.isEmpty());
        assertTrue(log.contains("HTTP 响应报文") && log.contains("已显示的半段回复") && log.contains("fixture stream failed"), log);
        assertTrue(log.contains("模型请求失败"), log);
        assertFalse(log.contains("## 模型回复 #"), log);
    }

    @Test void compactedRequestsAreRecordedWhileCheckpointKeepsFullHistory() throws Exception {
        configure("chat", "agent:\n  context-budget-tokens: 6000\n  token-counter: utf16\n  context-summarizer:\n    model: trace-summary\n");
        var history = new ArrayList<ChatMessage>();
        history.add(ChatMessage.system("旧系统提示会被刷新"));
        history.add(ChatMessage.user("需要始终保留的原始任务"));
        for (int i = 0; i < 15; i++) {
            history.add(ChatMessage.user("历史问题标记" + i));
            history.add(ChatMessage.assistant("历史回复标记" + i + "a".repeat(2400)));
        }
        SessionStore store = new SessionStore(workspace);
        store.save("compact-session", "openai-compatible", "trace-main", "COMPLETED", history);
        String summary = JSON.writeValueAsString(Map.of("goals", List.of("保留原始任务"), "constraints", List.of("用户原文完整保留"),
                "decisions", List.of(), "completed", List.of("旧轮次已完成"), "pending", List.of("继续任务"),
                "failures", List.of(), "references", List.of()));
        replies = (request, index) -> answer(request.path("model").asText().equals("trace-summary") ? summary : ANSWER);
        Result run = runJson(false, "resume", "compact-session", "继续压缩后的任务");
        assertEquals(0, run.code, run.err);
        JsonNode mainRequest = requests.stream().filter(r -> r.path("model").asText().equals("trace-main")).findFirst().orElseThrow();
        JsonNode messages = mainRequest.path("messages");
        assertTrue(messages.size() < history.size());
        assertTrue(messages.toString().contains("历史问题标记0"), "用户要求原文不能在压缩中删除");
        assertFalse(messages.toString().contains("历史回复标记0"));
        String log = Files.readString(Path.of(JSON.readTree(run.out).path("traceFile").asText()));
        assertTrue(log.contains("summarizer"), "摘要请求必须独立记录来源");
        for (JsonNode message : messages) {
            if (message.path("content").isTextual()) assertTrue(log.contains(message.path("content").asText().split("\n", 2)[0]), log);
        }
        assertTrue(store.load("compact-session").transcript().stream().anyMatch(m -> m.content().contains("历史回复标记0")));
        assertNotNull(store.load("compact-session").context(), "结构化摘要应与完整历史同时持久化");
    }

    @Test void resumeAndRewindKeepLogsInSameSessionAndLocalCommandsMakeNoRequests() throws Exception {
        configure("chat", "");
        new SessionStore(workspace).save("old-session", "openai-compatible", "trace-main", "COMPLETED",
                List.of(ChatMessage.system("旧系统"), ChatMessage.user("原始提问"), ChatMessage.assistant("原始回答")));
        replies = (request, index) -> answer(ANSWER);
        Result run = interactive("/resume old-session", "/logs", "继续第一项", "/logs", "/rewind 2", "改写第一项", "/logs", "/exit");
        assertEquals(0, run.code, run.err);
        assertEquals(2, requests.size(), "恢复、回退和查看日志不能调用模型");
        List<Path> files = TraceFiles.recent(workspace, "old-session", 10);
        assertEquals(2, files.size());
        assertTrue(files.stream().allMatch(f -> f.getParent().getFileName().toString().equals("old-session")));
        assertEquals(2, TraceFiles.recent(workspace, null, 10).size());
        assertTrue(run.out.contains("执行记录"), run.out);
        for (Path file : files) assertTrue(run.out.contains(file.toString()), run.out);
    }

    @Test void logsViewerIgnoresInvalidConfigurationAndNeverCreatesState() throws Exception {
        Files.writeString(config, "api-key: should-not-be-read\nunknown: [not-valid-yaml");
        Result run = command(new Input(), "--workspace", workspace.toString(), "--config", config.toString(), "--json", "logs");
        assertEquals(0, run.code, run.err);
        JsonNode output = JSON.readTree(run.out);
        assertTrue(output.path("files").isEmpty());
        assertTrue(output.path("enabled").isNull());
        assertEquals(workspace.toRealPath().resolve(".harness/logs").toString(), output.path("directory").asText());
        assertFalse(Files.exists(workspace.resolve(".harness")));
        assertTrue(requests.isEmpty());
        assertFalse(run.out.contains("should-not-be-read"));
        assertTrue(run.err.isBlank(), run.err);
    }

    @Test void disabledLogsKeepJsonAndTaskBehaviorWithoutCreatingLogDirectory() throws Exception {
        configure("chat", "trace:\n  enabled: false\n");
        replies = (request, index) -> answer(ANSWER);
        Result run = runJson(false, "run", "本任务不记录");
        assertEquals(0, run.code, run.err);
        assertTrue(JSON.readTree(run.out).path("traceFile").isNull());
        assertEquals(1, requests.size());
        assertFalse(Files.exists(workspace.resolve(".harness/logs")));
        assertTrue(run.err.isBlank(), run.err);
    }

    @Test void logWriteFailureDoesNotFailTaskOrPolluteJson() throws Exception {
        configure("chat", "");
        Files.createDirectory(workspace.resolve(".harness"));
        Files.writeString(workspace.resolve(".harness/logs"), "保留这个占位文件");
        replies = (request, index) -> answer(ANSWER);
        Result run = runJson(false, "run", "日志失败也要完成");
        assertEquals(0, run.code, run.err);
        assertEquals(ANSWER, JSON.readTree(run.out).path("answer").asText());
        assertTrue(JSON.readTree(run.out).path("traceFile").isNull());
        assertEquals(1, count(run.err, "执行日志记录不可用"));
        assertEquals(1, requests.size());
        assertEquals("保留这个占位文件", Files.readString(workspace.resolve(".harness/logs")));
    }

    @Test void logsEntryIsVisibleInHelpAndTabCompletionWithoutModelCalls() {
        Result result = command(new Input(), "--help");
        assertEquals(0, result.code);
        assertTrue(result.out.contains("logs"), result.out);
        List<String> candidates = CommandCompletion.candidates(List.of());
        assertEquals(List.of("/logs"), CommandCompletion.words("/lo", "/lo", candidates));
        assertTrue(requests.isEmpty());
    }

    private void configure(String selected, String extra) throws Exception {
        format = selected;
        Files.writeString(config, "provider: openai-compatible\napi-format: " + selected + "\nmodel: trace-main\napi-key: " + KEY
                + "\nbase-url: http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1\nweb:\n  enabled: false\npermissions:\n  mode: manual\n" + extra);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            String expected = "responses".equals(format) ? "/v1/responses" : "/v1/chat/completions";
            assertEquals(expected, exchange.getRequestURI().getPath());
            assertEquals("Bearer " + KEY, exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            Reply reply = replies.apply(request, requests.size());
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.type);
            exchange.sendResponseHeaders(reply.code, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable failure) {
            serverErrors.add(failure.toString());
            exchange.sendResponseHeaders(500, -1);
        } finally { exchange.close(); }
    }

    private Reply answer(String text) { return result(text, null, null, null); }
    private Reply tool(String id, String name, String arguments) { return result("准备执行工具", id, name, arguments); }
    private Reply result(String text, String id, String name, String arguments) {
        try {
            Object payload;
            if (format.equals("responses")) {
                List<Map<String, Object>> output = new ArrayList<>();
                output.add(Map.of("type", "message", "id", "message-fixture", "status", "completed", "role", "assistant",
                        "content", List.of(Map.of("type", "output_text", "text", text))));
                if (id != null) output.add(Map.of("type", "function_call", "id", "item-" + id, "call_id", id,
                        "status", "completed", "name", name, "arguments", arguments));
                payload = Map.of("id", "response-fixture", "status", "completed", "output", output,
                        "usage", Map.of("input_tokens", 17, "output_tokens", 5, "input_tokens_details", Map.of("cached_tokens", 9)));
            } else {
                Map<String, Object> message = new java.util.LinkedHashMap<>(Map.of("role", "assistant", "content", text));
                if (id != null) message.put("tool_calls", List.of(Map.of("id", id, "type", "function",
                        "function", Map.of("name", name, "arguments", arguments))));
                payload = Map.of("choices", List.of(Map.of("message", message, "finish_reason", id == null ? "stop" : "tool_calls")),
                        "usage", Map.of("prompt_tokens", 17, "completion_tokens", 5, "prompt_tokens_details", Map.of("cached_tokens", 9)));
            }
            return new Reply(200, "application/json", JSON.writeValueAsString(payload));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private Result runJson(boolean stream, String... command) {
        var arguments = arguments();
        arguments.add("--json");
        if (!stream) arguments.add("--no-stream");
        arguments.addAll(List.of(command));
        return command(new Input(), arguments.toArray(String[]::new));
    }
    private Result interactive(String... lines) {
        var arguments = arguments();
        arguments.add("--no-stream");
        return command(new Input(lines), arguments.toArray(String[]::new));
    }
    private ArrayList<String> arguments() {
        return new ArrayList<>(List.of("--workspace", workspace.toString(), "--config", config.toString(), "--quiet", "--no-color"));
    }
    private Result command(TerminalInput input, String... arguments) {
        var cli = new CommandLine(new HarnessCli(input, delays::add));
        StringWriter output = new StringWriter(), error = new StringWriter();
        cli.setOut(new PrintWriter(output, true)).setErr(new PrintWriter(error, true));
        return new Result(cli.execute(arguments), output.toString(), error.toString());
    }
    private static int count(String text, String needle) {
        int count = 0, position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) { count++; position += needle.length(); }
        return count;
    }
    private static void assertReferencesResolve(String log) {
        var references = Pattern.compile("\\(#((?:content|wire)-[0-9]+)\\)").matcher(log);
        int count = 0;
        while (references.find()) { assertTrue(log.contains("<a id=\"" + references.group(1) + "\"></a>"), log); count++; }
        assertTrue(count > 0, "重复内容应使用本文件锚点");
    }
    private record Reply(int code, String type, String body) { }
    private record Result(int code, String out, String err) { }
    private static final class Input implements TerminalInput {
        private final java.util.Iterator<String> lines;
        Input(String... lines) { this.lines = List.of(lines).iterator(); }
        @Override public String readLine() { return lines.hasNext() ? lines.next() : null; }
        @Override public boolean isTerminal() { return false; }
    }
}
