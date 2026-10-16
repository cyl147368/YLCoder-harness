package dev.codeharness.cli.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.HarnessConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 大小受限、以换行分隔的 JSON-RPC 客户端；仅协商 MCP tools 能力。 */
final class StdioMcpClient implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final Process process;
    private final Thread stdoutReader;
    private final Thread stderrReader;
    private final int maxBytes;
    private final long timeoutSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch cleanupFinished = new CountDownLatch(1);
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Set<Thread> writers = ConcurrentHashMap.newKeySet();

    StdioMcpClient(Path workspace, HarnessConfig.McpServer config, int maxBytes) throws IOException, InterruptedException {
        this.maxBytes = maxBytes;
        timeoutSeconds = config.timeoutSeconds;
        List<String> command = new ArrayList<>();
        command.add(config.command);
        command.addAll(config.args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile());
        for (var environment : config.environmentEnv.entrySet()) {
            String value = System.getenv(environment.getValue());
            if (value == null) throw new IOException("缺少 MCP 环境变量：" + environment.getValue());
            builder.environment().put(environment.getKey(), value);
        }
        try { process = builder.start(); }
        catch (IOException failure) { throw new IOException("无法启动配置的 MCP 服务端"); }
        stdoutReader = Thread.ofVirtual().name("mcp-stdout").unstarted(this::readMessages);
        stderrReader = Thread.ofVirtual().name("mcp-stderr").unstarted(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) { /* 持续读取输出，但不记录可能包含秘密的服务端诊断。 */ }
            } catch (IOException ignored) { }
        });
        stdoutReader.start();
        stderrReader.start();
        try {
            JsonNode initialized = request("initialize", Map.of("protocolVersion", "2025-11-25",
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "YLCoder-harness", "version", "0.1.0")));
            if (!VERSIONS.contains(initialized.path("protocolVersion").asText())
                    || !initialized.path("capabilities").path("tools").isObject()) {
                throw new IOException("MCP 服务端未协商出受支持的 tools 协议");
            }
            send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        } catch (IOException | InterruptedException failure) { close(); throw failure; }
    }

    JsonNode request(String method, Map<String, ?> params) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            close();
            throw new InterruptedException("MCP 请求已中断");
        }
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            // 请求时限包括子进程 stdin 的背压等待。
            Thread writer = Thread.ofVirtual().name("mcp-write").unstarted(() -> {
                try { send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)); }
                catch (IOException | RuntimeException failure) { response.completeExceptionally(failure); }
                finally { writers.remove(Thread.currentThread()); }
            });
            writers.add(writer);
            writer.start();
            JsonNode message = response.get(timeoutSeconds, TimeUnit.SECONDS);
            if (message.has("error")) throw new IOException("MCP 请求因 JSON-RPC 错误而失败");
            if (!message.has("result")) throw new IOException("MCP 响应缺少 result");
            return message.get("result");
        } catch (InterruptedException interrupted) {
            close();
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timedOut) {
            close();
            throw new IOException("MCP 请求超时；服务端已停止");
        } catch (ExecutionException failed) {
            close();
            throw new IOException("MCP 服务端连接失败");
        } catch (IOException failure) {
            close();
            throw failure;
        } finally { pending.remove(id); }
    }

    private void send(Map<String, ?> message) throws IOException {
        byte[] encoded = JSON.writeValueAsBytes(message);
        if (encoded.length > maxBytes) throw new IOException("MCP 请求超过消息大小上限");
        synchronized (writeLock) {
            if (closed.get()) throw new IOException("MCP 服务端已关闭");
            process.getOutputStream().write(encoded);
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
        }
    }

    private void readMessages() {
        try (InputStream input = process.getInputStream()) {
            while (!closed.get()) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int value;
                while ((value = input.read()) != -1 && value != '\n') {
                    if (line.size() >= maxBytes) throw new IOException("MCP 帧超过大小上限");
                    line.write(value);
                }
                if (value == -1) throw new IOException("MCP stdout 已关闭");
                if (line.size() == 0) throw new IOException("MCP stdout 包含空帧");
                JsonNode message = JSON.readTree(line.toByteArray());
                if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
                    throw new IOException("MCP JSON-RPC 帧无效");
                }
                if (message.has("method")) {
                    if (message.has("id")) {
                        // 未声明 roots、sampling 或 elicitation 能力。
                        send(Map.of("jsonrpc", "2.0", "id", message.get("id"), "error",
                                Map.of("code", -32601, "message", "不支持此客户端方法")));
                    }
                    continue;
                }
                if (!message.path("id").isIntegralNumber() || !message.path("id").canConvertToLong()
                        || message.has("result") == message.has("error")) throw new IOException("MCP 响应无效");
                CompletableFuture<JsonNode> response = pending.get(message.path("id").longValue());
                if (response == null) throw new IOException("MCP 响应 ID 不符合预期");
                response.complete(message);
            }
        } catch (IOException | RuntimeException failure) {
            pending.values().forEach(response -> response.completeExceptionally(new IOException("MCP 连接失败")));
            close();
        }
    }

    @Override public void close() {
        long deadline = System.nanoTime() + CLEANUP_NANOS;
        if (!closed.compareAndSet(false, true)) {
            // 读线程也可能率先检测到故障；外部 close 必须等同一次清理完成。
            // 正在被关闭的读线程不能反过来等待关闭方，否则 join 会互相等待。
            if (Thread.currentThread() != stdoutReader) await(cleanupFinished, deadline);
            return;
        }
        try {
            pending.values().forEach(response -> response.completeExceptionally(new IOException("MCP 服务端已停止")));
            stopProcess(process, deadline);
            join(stdoutReader, deadline);
            join(stderrReader, deadline);
            for (Thread writer : writers) join(writer, deadline);
        } finally { cleanupFinished.countDown(); }
    }

    static void stopProcess(Process process) {
        stopProcess(process, System.nanoTime() + CLEANUP_NANOS);
    }

    private static void stopProcess(Process process, long deadline) {
        // Windows 上的原生子孙进程枚举可能无限卡住。绝不将其放在
        // 请求或关闭处理路径上；直接终止必须独立于进程树发现。
        Thread.ofPlatform().daemon(true).name("mcp-descendant-cleanup").start(() -> {
            try (var descendants = process.descendants()) {
                descendants.limit(1024).forEach(child -> {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                });
            } catch (RuntimeException ignored) { /* 外部子孙进程清理仅为尽力处理。 */ }
        });
        // TerminateProcess 发出终止请求并不等于原生退出清理已经完成。
        // 同一个有界时限内等待 waitFor，同时分别关闭管道，避免单个阻塞关闭拖住其它清理。
        CountDownLatch completed = new CountDownLatch(5);
        cleanup("mcp-terminate", completed, () -> process.destroyForcibly());
        cleanup("mcp-exit-wait", completed, () -> {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) process.waitFor(remaining, TimeUnit.NANOSECONDS);
        });
        cleanup("mcp-stdin-close", completed, () -> process.getOutputStream().close());
        cleanup("mcp-stdout-close", completed, () -> process.getInputStream().close());
        cleanup("mcp-stderr-close", completed, () -> process.getErrorStream().close());
        await(completed, deadline);
    }

    @FunctionalInterface private interface Cleanup { void run() throws IOException, InterruptedException; }

    private static void cleanup(String name, CountDownLatch completed, Cleanup operation) {
        Thread.ofPlatform().daemon(true).name(name).start(() -> {
            try { operation.run(); }
            catch (IOException | InterruptedException | RuntimeException ignored) {
                // 不回显外部进程诊断，也不让清理失败覆盖最初的协议或超时错误。
            } finally { completed.countDown(); }
        });
    }

    private static void await(CountDownLatch completed, long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            while (completed.getCount() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                try { if (completed.await(remaining, TimeUnit.NANOSECONDS)) return; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void join(Thread worker, long deadline) {
        if (worker == Thread.currentThread()) return;
        boolean interrupted = Thread.interrupted();
        try {
            while (worker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                try { TimeUnit.NANOSECONDS.timedJoin(worker, remaining); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
