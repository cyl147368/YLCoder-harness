package dev.codeharness.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.HarnessConfig;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class McpToolsTest {
    @TempDir Path workspace;

    @Test void handshakesPaginatesPreservesSchemasNamesAndReturnsTextAndStructuredContent() throws Exception {
        HarnessConfig.Mcp config = config("normal");
        long pid;
        try (McpTools mcp = McpTools.open(workspace, config, 1000, request -> true)) {
            pid = pid();
            ToolRegistry registry = mcp.combine(ToolRegistry.empty());
            assertEquals(2, registry.definitions().size());
            Tool tool = registry.find(McpTools.toolName("fixture", "echo.text")).orElseThrow();
            assertTrue(tool.definition().name().matches("[A-Za-z0-9_-]{1,64}"));
            assertEquals(Map.of("value", Map.of("type", "string")), tool.definition().inputSchema().get("properties"));
            String output = tool.execute("{\"value\":\"hello\"}");
            assertTrue(output.contains("echo: hello"));
            assertTrue(output.contains("\"value\":\"hello\""));
            IOException error = assertThrows(IOException.class,
                    () -> registry.find(McpTools.toolName("fixture", "error-tool")).orElseThrow().execute("{}"));
            assertTrue(error.getMessage().contains("报告错误"));
            assertTrue(error.getMessage().contains("夹具拒绝"));
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call", "tools/call"),
                    Files.readAllLines(workspace.resolve("trace.txt")));
        }
        awaitStopped(pid);
    }

    @Test void closeWaitsForProcessExitAndImmediatelyReleasesItsWorkingDirectory() throws Exception {
        Path serverWorkspace = Files.createDirectory(workspace.resolve("mcp-workspace"));
        long pid;
        try (McpTools mcp = McpTools.open(serverWorkspace, config("normal", serverWorkspace), 1000, request -> true)) {
            pid = Long.parseLong(Files.readString(serverWorkspace.resolve("pid.txt")));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        // close 返回本身就是同步点；这里不能轮询退出，也不能重试文件操作。
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "close 返回时 MCP 进程必须已退出");
        Path released = workspace.resolve("released-workspace");
        Files.move(serverWorkspace, released);
        Files.delete(released.resolve("pid.txt"));
        Files.delete(released.resolve("trace.txt"));
        Files.delete(released);
    }

    @Test void deniesExternalCallsBeforeSendingAndBoundsToolOutput() throws Exception {
        try (McpTools mcp = McpTools.open(workspace, config("normal"), 128, request -> false)) {
            Tool tool = mcp.combine(ToolRegistry.empty()).find(McpTools.toolName("fixture", "echo.text")).orElseThrow();
            assertThrows(IOException.class, () -> tool.execute("{}"));
            assertFalse(Files.readString(workspace.resolve("trace.txt")).contains("tools/call"));
        }
        try (McpTools mcp = McpTools.open(workspace, config("normal"), 128, request -> true)) {
            String output = mcp.combine(ToolRegistry.empty()).find(McpTools.toolName("fixture", "echo.text"))
                    .orElseThrow().execute("{\"value\":\"" + "x".repeat(5000) + "\"}");
            assertTrue(output.length() <= 128, output);
            assertTrue(output.contains("已省略"));
        }
    }

    @Test void rejectsUnsupportedProtocolRepeatedPaginationAndCountOrSchemaOverflow() throws Exception {
        for (String mode : List.of("protocol", "repeat")) {
            assertThrows(IOException.class, () -> McpTools.open(workspace, config(mode), 1000, request -> true));
            awaitStopped(pid());
        }
        HarnessConfig.Mcp count = config("normal");
        count.maxTools = 1;
        assertThrows(IOException.class, () -> McpTools.open(workspace, count, 1000, request -> true));
        awaitStopped(pid());
        HarnessConfig.Mcp schema = config("schema");
        schema.maxSchemaBytes = 128;
        assertThrows(IOException.class, () -> McpTools.open(workspace, schema, 1000, request -> true));
        awaitStopped(pid());
    }

    @Test void rejectsOversizedMalformedAndSecretBearingErrorFramesWithoutLeaking() throws Exception {
        for (String mode : List.of("oversized", "malformed", "rpc-error")) {
            HarnessConfig.Mcp config = config(mode);
            config.maxMessageBytes = 1024;
            IOException failure = assertThrows(IOException.class, () -> McpTools.open(workspace, config, 1000, request -> true));
            assertFalse(failure.toString().contains("fixture-secret"));
            assertNull(failure.getCause());
            awaitStopped(pid());
        }
    }

    @Test void timeoutAndInterruptionStopTheSubprocess() throws Exception {
        HarnessConfig.Mcp config = config("hang");
        config.servers.get("fixture").timeoutSeconds = 1;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            IOException failure = assertThrows(IOException.class, () -> McpTools.open(workspace, config, 1000, request -> true));
            assertTrue(failure.getMessage().contains("超时"));
            awaitStopped(pid());
        });
        config.servers.get("fixture").timeoutSeconds = 30;
        Files.deleteIfExists(workspace.resolve("pid.txt"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                assertThrows(InterruptedException.class, () -> McpTools.open(workspace, config, 1000, request -> true));
                assertTrue(Thread.currentThread().isInterrupted());
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(workspace.resolve("pid.txt")) && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(Files.exists(workspace.resolve("pid.txt")));
            future.cancel(true);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            awaitStopped(pid());
        }
    }

    @Test void namespaceHashDistinguishesSanitizedNamesAndLongServerNames() {
        String first = McpTools.toolName("a".repeat(48), "echo.text");
        String second = McpTools.toolName("a".repeat(48), "echo/text");
        assertNotEquals(first, second);
        assertEquals(first, McpTools.toolName("a".repeat(48), "echo.text"));
        assertTrue(first.length() <= 64);
    }

    @Test void directTerminationDoesNotWaitForStalledNativeDescendantDiscovery() throws Exception {
        CountDownLatch enumerationStarted = new CountDownLatch(1);
        CountDownLatch releaseEnumeration = new CountDownLatch(1);
        CountDownLatch enumerationFinished = new CountDownLatch(1);
        AtomicBoolean destroyed = new AtomicBoolean();
        Process stalled = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { destroyed.set(true); }
            @Override public Process destroyForcibly() { destroyed.set(true); return this; }
            @Override public java.util.stream.Stream<ProcessHandle> descendants() {
                enumerationStarted.countDown();
                try { releaseEnumeration.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { enumerationFinished.countDown(); }
                return java.util.stream.Stream.empty();
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> StdioMcpClient.stopProcess(stalled));
            assertTrue(destroyed.get(), "必须先终止直接进程，再完成清理");
            assertTrue(enumerationStarted.await(2, TimeUnit.SECONDS));
            assertEquals(1, releaseEnumeration.getCount(), "close 返回之后，发现操作仍处于阻塞状态");
        } finally {
            releaseEnumeration.countDown();
            assertTrue(enumerationFinished.await(2, TimeUnit.SECONDS), "测试必须释放原生发现任务");
        }
    }

    @Test void directTerminationWaitsForNativeExitAndClosesAllPipes() throws Exception {
        ControlledProcess process = new ControlledProcess();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stopping = executor.submit(() -> StdioMcpClient.stopProcess(process));
            try {
                assertTrue(process.terminationRequested.await(1, TimeUnit.SECONDS), "必须请求终止直接进程");
                assertTrue(process.exitWaitStarted.await(1, TimeUnit.SECONDS), "必须等待原生进程退出");
                assertTrue(process.pipesClosed.await(1, TimeUnit.SECONDS), "等待退出期间必须独立关闭所有管道");
                assertFalse(stopping.isDone(), "终止请求已发出但进程尚未退出时，清理不得提前返回");
            } finally { process.releaseExit.countDown(); }
            stopping.get(2, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test void interruptedTerminationStillFinishesCleanupAndPreservesTheInterrupt() throws Exception {
        ControlledProcess process = new ControlledProcess();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stopping = executor.submit(() -> {
                Thread.currentThread().interrupt();
                StdioMcpClient.stopProcess(process);
                assertTrue(Thread.currentThread().isInterrupted(), "清理不得消耗调用方的中断状态");
            });
            try {
                assertTrue(process.exitWaitStarted.await(1, TimeUnit.SECONDS), "调用方中断后也必须等待原生进程退出");
                assertTrue(process.pipesClosed.await(1, TimeUnit.SECONDS));
                assertFalse(stopping.isDone());
            } finally { process.releaseExit.countDown(); }
            stopping.get(2, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    private HarnessConfig.Mcp config(String mode) throws IOException {
        return config(mode, workspace);
    }

    private HarnessConfig.Mcp config(String mode, Path serverWorkspace) throws IOException {
        // 每次启动都必须发布自己的就绪屏障，不能沿用之前的 PID。
        Files.deleteIfExists(serverWorkspace.resolve("pid.txt"));
        HarnessConfig.McpServer server = new HarnessConfig.McpServer();
        server.command = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        server.args = List.of("-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode,
                serverWorkspace.resolve("pid.txt").toString(), serverWorkspace.resolve("trace.txt").toString());
        server.timeoutSeconds = 5;
        HarnessConfig.Mcp config = new HarnessConfig.Mcp();
        config.servers = Map.of("fixture", server);
        return config;
    }

    private long pid() throws IOException { return Long.parseLong(Files.readString(workspace.resolve("pid.txt"))); }
    private static void awaitStopped(long pid) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "MCP 进程必须停止");
    }

    private static final class ControlledProcess extends Process {
        private final CountDownLatch terminationRequested = new CountDownLatch(1);
        private final CountDownLatch exitWaitStarted = new CountDownLatch(1);
        private final CountDownLatch releaseExit = new CountDownLatch(1);
        private final CountDownLatch pipesClosed = new CountDownLatch(3);
        private final java.io.OutputStream stdin = new java.io.ByteArrayOutputStream() {
            @Override public void close() { pipesClosed.countDown(); }
        };
        private final java.io.InputStream stdout = new java.io.ByteArrayInputStream(new byte[0]) {
            @Override public void close() { pipesClosed.countDown(); }
        };
        private final java.io.InputStream stderr = new java.io.ByteArrayInputStream(new byte[0]) {
            @Override public void close() { pipesClosed.countDown(); }
        };

        @Override public java.io.OutputStream getOutputStream() { return stdin; }
        @Override public java.io.InputStream getInputStream() { return stdout; }
        @Override public java.io.InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException {
            exitWaitStarted.countDown();
            releaseExit.await();
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            exitWaitStarted.countDown();
            return releaseExit.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (isAlive()) throw new IllegalThreadStateException("受控进程尚未退出");
            return 0;
        }
        @Override public boolean isAlive() { return releaseExit.getCount() != 0; }
        @Override public void destroy() { terminationRequested.countDown(); }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Path pid = Path.of(args[1]);
            Path unpublished = Files.createTempFile(pid.getParent(), "fixture-pid-", ".tmp");
            try {
                Files.writeString(unpublished, Long.toString(ProcessHandle.current().pid()));
                // 可观察到的 PID 路径必须包含完整值，然后
                // 父进程才能中断此进程。仅凭路径存在会产生竞态。
                Files.move(unpublished, pid, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(unpublished); }
            Path trace = Path.of(args[2]);
            ObjectMapper json = new ObjectMapper();
            boolean initialized = false;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonNode request = json.readTree(line);
                    String method = request.path("method").asText();
                    Files.writeString(trace, method + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    if (method.equals("notifications/initialized")) { initialized = true; continue; }
                    if (method.equals("initialize") && mode.equals("hang")) { Thread.sleep(60_000); continue; }
                    if (mode.equals("oversized")) { System.out.println("x".repeat(2000)); System.out.flush(); continue; }
                    if (mode.equals("malformed")) { System.out.println("fixture-secret"); System.out.flush(); continue; }
                    if (mode.equals("rpc-error")) {
                        System.out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                                "error", Map.of("code", -32000, "message", "fixture-secret"))));
                        System.out.flush(); continue;
                    }
                    Object result;
                    if (method.equals("initialize")) result = Map.of("protocolVersion", mode.equals("protocol") ? "unknown" : "2025-11-25",
                            "serverInfo", Map.of("name", "fixture", "version", "1"), "capabilities", Map.of("tools", Map.of()));
                    else if (method.equals("tools/list")) {
                        if (!initialized) throw new IllegalStateException("缺少 initialized 通知");
                        boolean second = request.path("params").has("cursor");
                        var schema = Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")));
                        var tool = Map.of("name", second ? "error-tool" : "echo.text", "description", "回显输入",
                                "inputSchema", mode.equals("schema") ? Map.of("type", "object", "description", "x".repeat(500)) : schema);
                        result = second && !mode.equals("repeat") ? Map.of("tools", List.of(tool))
                                : Map.of("tools", List.of(tool), "nextCursor", "second");
                    } else if (method.equals("tools/call")) {
                        boolean error = request.path("params").path("name").asText().equals("error-tool");
                        String value = request.path("params").path("arguments").path("value").asText();
                        result = Map.of("content", List.of(Map.of("type", "text", "text", error ? "夹具拒绝" : "echo: " + value)),
                                "structuredContent", Map.of("value", value), "isError", error);
                    } else throw new IllegalArgumentException("请求不符合预期");
                    System.out.write((json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result))
                            + "\n").getBytes(StandardCharsets.UTF_8));
                    System.out.flush();
                }
            }
        }
    }
}
