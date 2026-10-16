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
    private final Process process;
    private final int maxBytes;
    private final long timeoutSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

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
        Thread.ofVirtual().name("mcp-stdout").start(this::readMessages);
        Thread.ofVirtual().name("mcp-stderr").start(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) != -1) { /* 持续读取输出，但不记录可能包含秘密的服务端诊断。 */ }
            } catch (IOException ignored) { }
        });
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
            Thread.ofVirtual().name("mcp-write").start(() -> {
                try { send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)); }
                catch (IOException | RuntimeException failure) { response.completeExceptionally(failure); }
            });
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
        if (!closed.compareAndSet(false, true)) return;
        pending.values().forEach(response -> response.completeExceptionally(new IOException("MCP 服务端已停止")));
        stopProcess(process);
    }

    static void stopProcess(Process process) {
        // Windows 上的原生子孙进程枚举可能无限卡住。绝不将其放在
        // 请求或关闭处理路径上；直接终止必须独立于进程树发现。
        Thread.ofPlatform().daemon(true).name("mcp-descendant-cleanup").start(() -> {
            try (var descendants = process.descendants()) {
                descendants.limit(1024).forEach(child -> {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                });
            } catch (RuntimeException ignored) { /* 外部子孙进程清理仅为尽力处理。 */ }
        });
        try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        // 关闭管道可能等待阻塞的写入监视器；不能因此延长请求时限。
        Thread.ofPlatform().daemon(true).name("mcp-pipe-cleanup").start(() -> {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
        });
    }
}
