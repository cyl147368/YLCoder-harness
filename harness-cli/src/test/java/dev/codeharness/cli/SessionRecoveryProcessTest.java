package dev.codeharness.cli;

import dev.codeharness.core.agent.AgentListener;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryProcessTest {
    @TempDir Path workspace;
    private static final String ID = "crash-boundary";
    private static final String PROVIDER = "custom-fixture";
    private static final String MODEL = "local-fixture-model";
    private static final Map<String, String> OPAQUE_METADATA = Map.of(
            "gemini.thoughtSignature", "opaque+crash/signature==",
            "fixture.opaque", "token:with\\escape\nsecond-line");

    @Test void killedProcessAfterMutationResumesWithoutReplayingUnknownEffect() throws Exception {
        Path ready = workspace.resolve("mutation-ready"), marker = workspace.resolve("mutation.txt");
        Path log = workspace.resolve("child.log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                CrashFixture.class.getName(), workspace.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        byte[] pendingBytes;
        SessionStore.Session interrupted;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready), () -> "子进程未到达修改边界：" + diagnostics(log));
            assertTrue(child.isAlive(), "返回工具结果之前，夹具必须仍处于阻塞状态");
            SessionStore store = new SessionStore(workspace);
            interrupted = store.load(ID);
            pendingBytes = Files.readAllBytes(workspace.resolve(".harness/sessions/" + ID + ".json"));
            assertEquals(1, interrupted.transcript().stream().filter(m -> !m.toolCalls().isEmpty()).count());
            assertEquals(0, interrupted.transcript().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).count());
            assertOpaqueMetadata(interrupted.transcript());
            assertEquals(List.of("mutation"), Files.readAllLines(marker));
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "被终止的夹具必须释放会话文件和锁");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "夹具清理必须终止直接进程");
            }
        }

        SessionStore reopened = new SessionStore(workspace);
        assertArrayEquals(pendingBytes, Files.readAllBytes(workspace.resolve(".harness/sessions/" + ID + ".json")));
        SessionStore.Session loaded = reopened.load(ID);
        assertEquals(interrupted, loaded, "已提交的待完成检查点必须在进程死亡后保留");
        assertEquals(PROVIDER, loaded.provider());
        assertEquals(MODEL, loaded.model());
        assertOpaqueMetadata(loaded.transcript());
        AtomicInteger replays = new AtomicInteger(), modelRequests = new AtomicInteger();
        Tool edit = new Tool() {
            @Override public ToolDefinition definition() { return editDefinition(); }
            @Override public String execute(String arguments) throws Exception {
                replays.incrementAndGet();
                Files.writeString(marker, "mutation\n", StandardOpenOption.APPEND);
                return "saved";
            }
        };
        ChatModel model = request -> {
            modelRequests.incrementAndGet();
            assertOpaqueMetadata(request.messages());
            List<ChatMessage> results = request.messages().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).toList();
            assertEquals(1, results.size());
            assertEquals("pending-edit", results.getFirst().toolCallId());
            assertTrue(results.getFirst().content().contains("实际影响未知"));
            return ChatResponse.text("已验证现有修改。");
        };
        AgentResult result;
        try (SessionStore.Lease ignored = reopened.lock(ID)) {
            result = new CodeAgent(model, new ToolRegistry(List.of(edit)))
                    .resume(loaded.transcript(), AgentRunOptions.defaults(), persistence(reopened));
            reopened.save(ID, loaded.provider(), loaded.model(), result.status().name(), result.transcript());
        }
        assertEquals(AgentResult.Status.COMPLETED, result.status(), result.error());
        assertEquals(1, modelRequests.get());
        assertEquals(0, replays.get());
        assertEquals(List.of("mutation"), Files.readAllLines(marker));
        SessionStore.Session completed = new SessionStore(workspace).load(ID);
        assertEquals(PROVIDER, completed.provider());
        assertEquals(MODEL, completed.model());
        assertEquals("COMPLETED", completed.status());
        assertEquals(result.transcript(), completed.transcript());
        assertOpaqueMetadata(completed.transcript());
        assertTrue(completed.transcript().stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL
                && m.content().contains("实际影响未知")));
    }

    private static AgentListener persistence(SessionStore store) {
        return new AgentListener() {
            @Override public void onCheckpoint(List<ChatMessage> transcript) {
                try { store.save(ID, PROVIDER, MODEL, "RUNNING", transcript); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        };
    }

    private static ToolDefinition editDefinition() { return new ToolDefinition("edit", "修改夹具", Map.of("type", "object")); }
    private static void assertOpaqueMetadata(List<ChatMessage> transcript) {
        List<ToolCall> calls = transcript.stream().flatMap(message -> message.toolCalls().stream()).toList();
        assertEquals(1, calls.size());
        assertEquals("pending-edit", calls.getFirst().id());
        assertEquals(OPAQUE_METADATA, calls.getFirst().providerMetadata(), "不透明工具元数据必须精确保留");
    }
    private static String diagnostics(Path log) {
        try { return Files.readString(log); }
        catch (IOException ignored) { return "no diagnostics"; }
    }

    /** 在实际可见修改之后、任何结果检查点之前终止独立 JVM。 */
    public static final class CrashFixture {
        public static void main(String[] args) throws Exception {
            Path workspace = Path.of(args[0]);
            SessionStore store = new SessionStore(workspace);
            ChatModel model = request -> new ChatResponse("Editing", List.of(new ToolCall("pending-edit", "edit", "{}", OPAQUE_METADATA)),
                    TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            Tool edit = new Tool() {
                @Override public ToolDefinition definition() { return editDefinition(); }
                @Override public String execute(String arguments) throws Exception {
                    Files.writeString(workspace.resolve("mutation.txt"), "mutation\n", StandardOpenOption.CREATE_NEW);
                    Files.writeString(workspace.resolve("mutation-ready"), "ready", StandardOpenOption.CREATE_NEW);
                    new CountDownLatch(1).await();
                    throw new IllegalStateException("崩溃夹具意外继续执行");
                }
            };
            try (SessionStore.Lease ignored = store.lock(ID)) {
                AgentResult unexpected = new CodeAgent(model, new ToolRegistry(List.of(edit)))
                        .run("执行一次修改", AgentRunOptions.defaults(), persistence(store));
                throw new IllegalStateException("夹具未在修改处阻塞：" + unexpected.status() + " " + unexpected.error());
            }
        }
    }
}
