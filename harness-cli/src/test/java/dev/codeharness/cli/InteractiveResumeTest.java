package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** 使用临时工作区和本地 HTTP 夹具验证恢复，不接触用户配置或真实厂商。 */
class InteractiveResumeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;
    private HttpServer server;
    private Path config;
    private SessionStore store;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private boolean failFirstStream;
    private boolean removeSessionsOnReply;

    @BeforeEach void start() throws Exception {
        store = new SessionStore(workspace);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            if (removeSessionsOnReply) {
                // 仅删除本测试临时工作区，复现外部清理连同会话目录一起移除。
                try (var paths = Files.walk(workspace.resolve(".harness/sessions"))) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            if (failFirstStream && requests.size() == 1 && requests.getFirst().path("stream").asBoolean()) {
                String events = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"error\":{\"message\":\"private-upstream-body\"}}\n\n";
                byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                    "message", Map.of("role", "assistant", "content", "本地回复"), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 11, "completion_tokens", 3)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        config = workspace.resolve("offline.yaml");
        Files.writeString(config, "provider: openai-compatible\nmodel: fresh-model\napi-key-env: ''\nbase-url: http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1\npermissions:\n  auto-review: false\n");
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void emptyListBadSelectionAndHelpRemainLocal() throws Exception {
        Result result = run("/resume\n/resume 1\n/resume 99999999999999999999\n/resume ../outside\n/help\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(result.out.contains("当前工作区没有可恢复的会话"));
        assertTrue(result.out.contains("序号无效"));
        assertTrue(result.out.contains("当前会话未改变"));
        assertTrue(result.out.contains("/resume 序号或会话ID"));
        assertTrue(requests.isEmpty());
        assertTrue(store.recent(20).isEmpty());
    }

    @Test void deletedSessionDirectoryKeepsHistoryAndLocalCommandsWithoutReplayingWorkOrExitingEarly() throws Exception {
        removeSessionsOnReply = true;
        Result result = run("先完成任务\n/history all\n禁止重复执行的后续任务\n/help\n/exit\n");
        assertEquals(1, result.code, result.err);
        assertTrue(result.err.contains("会话未保存"), result.err);
        assertTrue(result.out.contains("历史对话"), result.out);
        assertTrue(result.out.contains("先完成任务") && result.out.contains("本地回复"), result.out);
        assertTrue(result.out.contains("已暂停新任务"), result.out);
        assertTrue(result.out.contains("/resume 序号或会话ID"), "存储失败后仍应读取 /help 和 /exit");
        assertEquals(1, requests.size(), "不能在存储损坏后继续模型请求或重放操作");
        assertFalse(Files.exists(workspace.resolve(".harness/sessions")), "不能静默重建目录并复用已删除的锁");
    }

    @Test void selectingListedSessionDoesNotRequestModelOrRewriteCheckpoint() throws Exception {
        save("older", "old-model", "较早任务");
        save("latest", "saved-model", "任务\033[2J\n另起一行");
        Files.setLastModifiedTime(checkpoint("older"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        byte[] before = Files.readAllBytes(checkpoint("latest"));
        Result result = run("/resume\n/resume 1\n/status\n/resume latest\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(result.out.indexOf("saved-model") < result.out.indexOf("old-model"));
        assertTrue(result.out.contains("已恢复会话：latest"));
        assertTrue(result.out.contains("历史对话"));
        assertTrue(result.out.contains("已进入该会话"));
        assertTrue(result.out.contains("latest（已保存）"));
        assertTrue(result.out.contains("已在此会话中"));
        assertFalse(result.out.contains("\033"), "会话摘要不能注入终端控制字符");
        assertArrayEquals(before, Files.readAllBytes(checkpoint("latest")));
        assertTrue(requests.isEmpty(), "列出和切换均不能主动请求模型");
        try (var lease = store.lock("latest")) { assertTrue(lease.lock().isValid(), "退出必须释放恢复会话锁"); }
    }

    @Test void restoredConversationAndHistoryPagesRemainLocalAndContinuationUsesSameSession() throws Exception {
        store.save("history", "openai-compatible", "saved-model", "COMPLETED", List.of(
                ChatMessage.system("private-system"), ChatMessage.user("旧问题"), ChatMessage.assistant("旧回复\n第二行")));
        Result result = run("/resume history\n/history\n/history all\n/history 999\n继续旧任务\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(result.out.indexOf("已恢复会话：history") < result.out.indexOf("旧问题"));
        assertTrue(result.out.contains("旧回复" + System.lineSeparator() + "    第二行"));
        assertFalse(result.out.contains("private-system"));
        assertTrue(result.out.contains("页码无效"));
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().path("messages").toString().contains("旧回复"));
        assertEquals(List.of("旧问题", "继续旧任务"), users(store.load("history")));
    }

    @Test void switchingRestoresHistoryModelAndLocksWithoutMixingCurrentConversation() throws Exception {
        save("saved", "saved-model", "原任务");
        var lines = List.of("新会话任务", "/resume saved", "/status", "继续原任务", "/exit").iterator();
        TerminalInput input = new TerminalInput() {
            private int reads;
            public String readLine() throws java.io.IOException {
                if (++reads == 3) {
                    assertThrows(java.io.IOException.class, () -> store.lock("saved"), "恢复后整个输入等待期间都必须持锁");
                    String original = store.recent(20).stream().filter(s -> !s.id().equals("saved")).findFirst().orElseThrow().id();
                    try (var lease = store.lock(original)) { assertTrue(lease.lock().isValid(), "切换后释放旧会话锁"); }
                }
                return lines.hasNext() ? lines.next() : null;
            }
            public boolean isTerminal() { return false; }
        };
        Result result = run(input);
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertEquals("fresh-model", requests.getFirst().path("model").asText());
        assertEquals("saved-model", requests.getLast().path("model").asText());
        String messages = requests.getLast().path("messages").toString();
        assertTrue(messages.contains("原任务"));
        assertTrue(messages.contains("继续原任务"));
        assertFalse(messages.contains("新会话任务"));
        assertEquals(List.of("原任务", "继续原任务"), users(store.load("saved")));
        assertEquals(2, store.recent(20).size());
        assertTrue(result.out.contains("输入 token —"), "恢复后本轮用量清空");
        assertTrue(result.out.contains("11"), "进程累计仍保留切换前的请求");
    }

    @Test void invalidBusyAndUnsupportedSessionsLeaveCurrentConversationUsable() throws Exception {
        save("busy", "saved-model", "不可读入当前会话");
        store.save("unsupported", "missing-provider-fixture", "bad-model", "COMPLETED", List.of(ChatMessage.user("秘密旧内容")));
        Files.writeString(checkpoint("broken"), "not-json-with-private-content");
        try (var lease = store.lock("busy")) {
            Result result = run("当前任务\n/resume missing\n/resume broken\n/resume busy\n/resume unsupported\n下一任务\n/exit\n");
            assertEquals(0, result.code, result.err);
            assertEquals(4, result.out.split("当前会话未改变", -1).length - 1);
            assertFalse(result.out.contains("private-content"));
            assertFalse(result.out.contains("秘密旧内容"));
        }
        assertEquals(2, requests.size());
        assertEquals("fresh-model", requests.getLast().path("model").asText());
        assertTrue(requests.getLast().path("messages").toString().contains("当前任务"));
        assertTrue(requests.getLast().path("messages").toString().contains("下一任务"));
        assertFalse(requests.getLast().path("messages").toString().contains("不可读入当前会话"));
        try (var lease = store.lock("unsupported")) { assertTrue(lease.lock().isValid(), "初始化失败也必须释放目标会话锁"); }
    }

    @Test void explicitModelOverrideStillWinsWhenResuming() throws Exception {
        save("saved", "saved-model", "原任务");
        Result result = run(input("/resume saved\n继续\n/exit\n"), "--model", "override-model");
        assertEquals(0, result.code, result.err);
        assertEquals(1, requests.size());
        assertEquals("override-model", requests.getFirst().path("model").asText());
        assertEquals("override-model", store.load("saved").model());
    }

    @Test void interruptedToolIsNotReplayedOnResume() throws Exception {
        ToolCall pending = new ToolCall("pending-write", "write_file", "{\"path\":\"must-not-exist.txt\",\"content\":\"unexpected\"}");
        store.save("interrupted", "openai-compatible", "saved-model", "RUNNING",
                List.of(ChatMessage.user("原任务"), ChatMessage.assistantToolCalls("", List.of(pending))));
        Result result = run("/resume interrupted\n检查当前状态\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertFalse(Files.exists(workspace.resolve("must-not-exist.txt")));
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().path("messages").toString().contains("pending-write"));
        assertTrue(requests.getFirst().path("messages").toString().contains("未知"));
    }

    @Test void listingSkipsInvalidCheckpointsAndRespectsLimit() throws Exception {
        save("valid", "saved-model", "原任务");
        Files.writeString(checkpoint("broken"), "{}");
        Files.writeString(workspace.resolve(".harness/sessions/checkpoint-temp.tmp"), "{}");
        Files.createDirectory(checkpoint("directory"));
        assertEquals(List.of("valid"), store.recent(20).stream().map(SessionStore.Summary::id).toList());
        assertThrows(IllegalArgumentException.class, () -> store.recent(0));
        assertThrows(IllegalArgumentException.class, () -> store.recent(51));
    }

    @Test void resumedStreamErrorRetriesSameRequestOnceAndPreservesHistory() throws Exception {
        save("saved", "saved-model", "原任务");
        failFirstStream = true;
        var lines = List.of("/resume saved", "hi", "hi again", "/exit").iterator();
        TerminalInput input = new TerminalInput() {
            private int reads;
            public String readLine() {
                if (++reads == 3) assertEquals(2, requests.size(), "无需重复输入任务即可完成一次非流式重试");
                return lines.hasNext() ? lines.next() : null;
            }
            public boolean isTerminal() { return false; }
        };
        Result result = runStreaming(input);
        assertEquals(0, result.code, result.err);
        assertEquals(3, requests.size());
        assertTrue(requests.getFirst().path("stream").asBoolean());
        assertFalse(requests.getLast().path("stream").asBoolean());
        assertEquals(requests.getFirst().path("messages"), requests.get(1).path("messages"));
        assertFalse(result.err.contains("错误："));
        assertFalse(result.err.contains("private-upstream-body"));
        assertTrue(result.out.contains("本地回复"));
        assertTrue(requests.getLast().path("messages").toString().contains("原任务"));
        assertEquals(List.of("原任务", "hi", "hi again"), users(store.load("saved")));
    }

    @Test void interactiveStreamControlsAndConfigRequireNoModelCallUntilTask() throws Exception {
        save("saved", "saved-model", "原任务");
        Result result = runStreaming(input("/stream\n/stream bad\n/stream off\n/resume saved\n非流式任务\n/stream on\n流式任务\n/exit\n"));
        assertEquals(0, result.code, result.err);
        assertEquals(2, requests.size());
        assertFalse(requests.getFirst().path("stream").asBoolean());
        assertTrue(requests.getLast().path("stream").asBoolean());
        assertTrue(result.out.contains("用法：/stream"));
        Files.writeString(config, Files.readString(config) + "agent:\n  stream: false\n");
        result = runStreaming(input("配置关闭流式\n/exit\n"));
        assertEquals(0, result.code, result.err);
        assertFalse(requests.getLast().path("stream").asBoolean());
    }

    private void save(String id, String model, String task) throws Exception {
        store.save(id, "openai-compatible", model, "COMPLETED", List.of(ChatMessage.user(task)));
    }

    @Test void rewindCreatesPersistentBranchWithoutChangingOriginalOrReplayingTools() throws Exception {
        ToolCall read = new ToolCall("read-old", "read_file", "{\"path\":\"old.txt\"}");
        store.save("original", "openai-compatible", "saved-model", "COMPLETED", List.of(
                ChatMessage.system("旧系统"), ChatMessage.user("保留的提问"),
                ChatMessage.assistantToolCalls("", List.of(read)), ChatMessage.toolResult(read, "历史文件结果"),
                ChatMessage.assistant("保留的回答"), ChatMessage.user("丢弃的提问"), ChatMessage.assistant("丢弃的回答")));
        byte[] original = Files.readAllBytes(checkpoint("original"));
        Files.writeString(workspace.resolve("changed.txt"), "文件修改不能被对话回退撤销");
        Result result = run("/resume original\n/rewind\n2\n新的分支任务\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(result.out.contains("已回退到第 2 条提问之前"));
        assertArrayEquals(original, Files.readAllBytes(checkpoint("original")));
        assertEquals(1, requests.size());
        String wire = requests.getFirst().path("messages").toString();
        assertTrue(wire.contains("保留的回答"));
        assertTrue(wire.contains("历史文件结果"));
        assertFalse(wire.contains("丢弃的"));
        assertTrue(wire.contains("新的分支任务"));
        assertEquals("文件修改不能被对话回退撤销", Files.readString(workspace.resolve("changed.txt")));
        var branch = store.recent(20).stream().filter(s -> !s.id().equals("original")).findFirst().orElseThrow();
        assertEquals(List.of("保留的提问", "新的分支任务"), users(store.load(branch.id())));
        try (var lease = store.lock(branch.id())) { assertTrue(lease.lock().isValid()); }
    }

    @Test void rewindToStartSavesReadySessionAndCanResumeWithoutModel() throws Exception {
        save("original", "saved-model", "旧任务");
        byte[] original = Files.readAllBytes(checkpoint("original"));
        Result result = run("/resume original\n/rewind 1\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(requests.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(checkpoint("original")));
        var branch = store.recent(20).stream().filter(s -> !s.id().equals("original")).findFirst().orElseThrow();
        assertEquals("READY", branch.status());
        assertTrue(users(store.load(branch.id())).isEmpty());
        Result resumed = run("/resume " + branch.id() + "\n新开始\n/exit\n");
        assertEquals(0, resumed.code, resumed.err);
        assertEquals(List.of("新开始"), users(store.load(branch.id())));
        assertFalse(requests.getFirst().path("messages").toString().contains("旧任务"));
    }

    @Test void invalidAndCancelledRewindStayLocalAndPreserveSession() throws Exception {
        save("original", "saved-model", "旧任务");
        byte[] original = Files.readAllBytes(checkpoint("original"));
        Result result = run("/rewind\n/resume original\n/rewind 0\n/rewind 999999999999999\n/rewind bad\n/rewind\n\n/exit\n");
        assertEquals(0, result.code, result.err);
        assertTrue(result.out.contains("当前没有可回退的提问"));
        assertTrue(result.out.contains("已取消回退"));
        assertTrue(result.out.contains("回退序号无效"));
        assertEquals(1, store.recent(20).size());
        assertArrayEquals(original, Files.readAllBytes(checkpoint("original")));
        assertTrue(requests.isEmpty());
    }
    private Path checkpoint(String id) { return workspace.resolve(".harness/sessions/" + id + ".json"); }
    private List<String> users(SessionStore.Session session) {
        return session.transcript().stream().filter(m -> m.role() == ChatMessage.Role.USER).map(ChatMessage::content).toList();
    }
    private TerminalInput input(String lines) {
        return TerminalInput.utf8(new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)));
    }
    private Result run(String lines) { return run(input(lines)); }
    private Result run(TerminalInput input, String... extra) {
        return runMode(input, false, extra);
    }
    private Result runStreaming(TerminalInput input) { return runMode(input, true); }
    private Result runMode(TerminalInput input, boolean stream, String... extra) {
        var cli = new CommandLine(new HarnessCli(input));
        StringWriter out = new StringWriter(), err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        var args = new ArrayList<>(List.of("--workspace", workspace.toString(), "--config", config.toString(),
                "--no-color", "--quiet"));
        if (!stream) args.add("--no-stream");
        args.addAll(List.of(extra));
        return new Result(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
