package dev.codeharness.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 一个任务的本地可读追踪；日志不可用时只提示一次，绝不阻断模型或工具执行。 */
public final class ExecutionTrace implements AutoCloseable {
    static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    static final int MAX_CONTENT_CHARS = 1_048_576;
    private static final int MAX_REFERENCES = 65_536;
    private static final String IO_WARNING = "执行日志记录不可用，已停止本次任务的日志；任务会继续执行。";
    private static final String LIMIT_WARNING = "执行日志达到本次任务的大小或索引上限，已停止追加；任务会继续执行。";
    private static final String LIMIT_MARKER = "\n## 日志停止记录\n\n已达到单次任务 32 MiB 或 65,536 条内容引用的保护上限。之后的请求、回复和工具过程未被记录；模型任务继续执行。\n";
    private final Path workspace;
    private final String sessionId;
    private final int toolResultMaxChars;
    private final Consumer<String> warning;
    private final TraceRedactor redactor;
    private final Map<String, String> references = new HashMap<>();
    private final WirePayloads wirePayloads = new WirePayloads();
    private final Map<String, String> wireOrigins = new HashMap<>();
    private final Map<Long, String> requestSources = new HashMap<>();
    private final int fileLimit;
    private TraceFile output;
    private Path path;
    private boolean disabled;
    private boolean closed;
    private boolean warned;
    private boolean finished;
    private long requests;
    private long contentSequence;
    private long toolSequence;

    private ExecutionTrace(Path workspace, String sessionId, int toolResultMaxChars, Collection<String> secrets,
                           Consumer<String> warning, boolean disabled, int fileLimit) {
        this.workspace = workspace;
        this.sessionId = sessionId;
        this.toolResultMaxChars = Math.max(128, Math.min(32_000, toolResultMaxChars));
        this.warning = warning;
        this.redactor = new TraceRedactor(secrets);
        this.disabled = disabled;
        this.fileLimit = fileLimit;
    }

    /** 仅建立记录器；收到首个事件后才建立目录和任务文件。 */
    public static ExecutionTrace open(Path workspace, String sessionId, int toolResultMaxChars,
                                      Collection<String> secrets, Consumer<String> warning) {
        try {
            return new ExecutionTrace(workspace, sessionId, toolResultMaxChars, secrets, warning, false, MAX_FILE_BYTES);
        } catch (RuntimeException failure) {
            notifySafely(warning, IO_WARNING);
            return disabled();
        }
    }

    public static ExecutionTrace disabled() {
        return new ExecutionTrace(null, null, 2_000, List.of(), null, true, MAX_FILE_BYTES);
    }

    /** 可追加延迟解析的独立审核连接凭据；不回写之前已完成的事件。 */
    public synchronized void addSecrets(Collection<String> secrets) {
        if (disabled || closed) return;
        try { redactor.add(secrets); }
        catch (RuntimeException failure) { stop(false); }
    }

    /** 每次真实调用使用独立编号，包括重试、降级及审核请求。 */
    public synchronized long request(String source, String provider, String model, boolean streaming,
                                     int iteration, ChatRequest request) {
        return request(source, provider, model, streaming, iteration, request, true);
    }

    public synchronized long request(String source, String provider, String model, boolean streaming,
                                     int iteration, ChatRequest request, boolean includeLogicalBody) {
        long id = ++requests;
        if (disabled || closed) return id;
        try {
            if (!ensureOpen()) return id;
            String role = "reviewer".equals(source) ? "reviewer" : "summarizer".equals(source) ? "summarizer" : "agent";
            if (requestSources.size() >= MAX_REFERENCES) throw new LimitReached();
            requestSources.put(id, role);
            StringBuilder event = new StringBuilder();
            event.append("\n<a id=\"request-").append(id).append("\"></a>\n\n## 模型请求 #").append(id).append("\n\n");
            field(event, "时间（UTC）", Instant.now().toString());
            field(event, "来源", role);
            field(event, "厂商", provider);
            field(event, "模型", model);
            field(event, "调用方式", streaming ? "流式" : "非流式");
            field(event, "智能体迭代", Integer.toString(iteration));
            field(event, "maxOutputTokens", request.maxOutputTokens() == null ? "厂商默认" : request.maxOutputTokens().toString());
            field(event, "消息数量", Integer.toString(request.messages().size()));
            if (includeLogicalBody) logicalBody(event, request);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
        return id;
    }

    private void logicalBody(StringBuilder event, ChatRequest request) throws Exception {
            int index = 0;
            for (ChatMessage message : request.messages()) {
                event.append("\n### 消息 ").append(++index).append(" · ").append(message.role()).append("\n\n");
                if (message.toolCallId() != null) field(event, "工具调用 ID", message.toolCallId());
                if (message.toolName() != null) field(event, "工具", message.toolName());
                body(event, "消息正文", message.content(), message.role() == ChatMessage.Role.TOOL ? toolResultMaxChars : MAX_CONTENT_CHARS, false);
                for (ToolCall call : message.toolCalls()) renderToolCall(event, call);
                metadata(event, message.providerMetadata().size());
                if (event.length() > fileLimit) throw new LimitReached();
            }
            event.append("\n### 工具定义（").append(request.tools().size()).append(" 项）\n\n");
            for (ToolDefinition tool : request.tools()) {
                field(event, "工具", tool.name());
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("name", tool.name());
                schema.put("description", tool.description());
                schema.put("inputSchema", tool.inputSchema());
                body(event, "工具定义", TraceRedactor.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(schema), MAX_CONTENT_CHARS, false);
                if (event.length() > fileLimit) throw new LimitReached();
            }
    }

    /** 没有接入 HTTP 观察器的自定义 SPI 仍显示逻辑消息，并明确标注覆盖范围。 */
    public synchronized void logicalFallback(long requestId, ChatRequest request) {
        if (disabled || closed) return;
        try {
            StringBuilder event = responseHeader(requestId, "逻辑请求备用记录", -1);
            event.append("本次适配器未提供 HTTP 报文；下面是逻辑消息，不能视为实际网络报文。\n\n");
            logicalBody(event, request);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void wireRequest(long requestId, int attempt, String method, String endpoint, String value) {
        wire(requestId, attempt, "HTTP 请求报文", method + " " + endpoint, value, false);
    }

    public synchronized void wireResponse(long requestId, int attempt, int status, String contentType,
                                           String value, boolean truncated, String state) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = responseHeader(requestId, "HTTP 响应状态", -1);
            field(event, "HTTP 尝试", Integer.toString(attempt));
            field(event, "传输信息", "HTTP " + (status == 0 ? "未收到响应头" : status));
            field(event, "响应类型", contentType);
            field(event, "读取状态", state);
            if (truncated) event.append("**报文已截断：响应捕获达到上限，不能保证错误详情完整。**\n\n");
            appendResponseError(event, value, status);
            event.append("\n回答正文、工具调用及用量见同编号的“模型回复”；中断时查看“未完成回复片段”和“模型请求失败”。\n\n");
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    /** 只摘取结构化错误字段；正常响应直接使用适配器的解析结果，不再保存 JSON/SSE 原文。 */
    private void appendResponseError(StringBuilder event, String raw, int status) {
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode value = redactor.json(raw);
            appendErrorFields(event, value, status);
        } catch (Exception notJson) {
            // SSE 允许一个事件包含多行 data，只处理错误事件，不回显 delta 或原始事件行。
            StringBuilder data = new StringBuilder();
            for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                if (line.isEmpty()) {
                    if (appendSseError(event, data)) return;
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    String part = line.substring(5);
                    data.append(part.startsWith(" ") ? part.substring(1) : part);
                }
            }
            appendSseError(event, data);
        }
    }

    private boolean appendSseError(StringBuilder event, StringBuilder data) {
        if (data.isEmpty()) return false;
        try { return appendErrorFields(event, redactor.json(data.toString()), 200); }
        catch (Exception invalid) { return false; }
    }

    private boolean appendErrorFields(StringBuilder event, JsonNode value, int status) {
        if (value == null || !value.isObject()) return false;
        JsonNode error = value.path("error");
        if (error.isMissingNode() || error.isNull()) error = value.path("response").path("error");
        boolean failed = status >= 400 || !error.isMissingNode() && !error.isNull()
                || "error".equals(value.path("type").asText());
        if (!failed) return false;
        if (error.isMissingNode() || error.isNull()) error = value;
        if (error.isTextual()) field(event, "厂商错误", error.asText());
        else {
            errorField(event, "厂商错误", error, "message");
            errorField(event, "错误类型", error, "type");
            errorField(event, "错误代码", error, "code");
        }
        JsonNode owner = error.has("request_id") ? error
                : value.path("response").has("request_id") ? value.path("response") : value;
        errorField(event, "请求 ID", owner, "request_id");
        return true;
    }

    private void errorField(StringBuilder event, String label, JsonNode value, String key) {
        JsonNode fieldValue = value.path(key);
        if (fieldValue.isValueNode() && !fieldValue.isNull()) field(event, label, fieldValue.asText());
    }

    private void wire(long requestId, int attempt, String title, String details, String value, boolean truncated) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = responseHeader(requestId, title, -1);
            field(event, "HTTP 尝试", Integer.toString(attempt));
            field(event, "传输信息", details);
            String raw = value == null ? "" : value;
            field(event, "捕获长度", raw.length() + " 字符");
            if (raw.length() > 8 * 1024 * 1024) {
                // 先脱敏再截断，避免截断已知凭据后失去匹配条件。
                raw = redactor.text(raw);
                raw = raw.substring(0, Math.min(raw.length(), 8 * 1024 * 1024));
                truncated = true;
            }
            if (truncated) event.append("**报文已截断：达到 8 MiB 字节/字符保护上限，不能重建完整报文。**\n\n");
            JsonNode safe;
            try { safe = redactor.json(raw); }
            catch (Exception nonJson) { safe = TraceRedactor.JSON.getNodeFactory().textNode(redactor.text(raw)); }
            field(event, "正文格式", safe.isContainerNode() ? "JSON（保留字段；已脱敏、格式化）" : "文本或 SSE（已脱敏）");
            var projected = wirePayloads.project(safe);
            var locations = projected.path("content_locations").fields();
            while (locations.hasNext()) {
                var entry = locations.next();
                wireOrigins.put(entry.getKey(), title + " #" + requestId + "（尝试 " + attempt
                        + "），body" + entry.getValue().asText());
            }
            event.append("\n报文内容（body）；首次内容直接展示，重复内容使用引用：\n\n");
            codeBlock(event, TraceRedactor.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(projected));
            var refs = projected.path("references").fields();
            if (refs.hasNext()) event.append("**重复内容定位（引用不是空值）：**\n\n");
            var linked = new java.util.HashSet<String>();
            while (refs.hasNext()) {
                String reference = refs.next().getValue().asText();
                if (linked.add(reference)) event.append("- ").append(reference).append(" → ")
                        .append(inline(wireOrigins.get(reference))).append("\n");
            }
            event.append("\n\n");
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void response(long requestId, ChatResponse response, long elapsedMillis) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = responseHeader(requestId, "模型回复", elapsedMillis);
            field(event, "结束原因", response.finishReason().name());
            usage(event, response.usage());
            String source = requestSources.remove(requestId);
            if ("reviewer".equals(source)) {
                renderReview(event, response);
            } else {
                if (!response.text().isEmpty()) body(event, "回复正文", response.text(), MAX_CONTENT_CHARS, false);
                else event.append(response.toolCalls().isEmpty()
                        ? "厂商未返回可展示的正文。\n\n" : "本次回复请求执行工具，没有文字正文。\n\n");
                for (ToolCall call : response.toolCalls()) renderToolCall(event, call);
            }
            metadata(event, response.providerMetadata().size());
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    /** 流中断时记录已经显示的文字，避免要求用户阅读 SSE 原始事件。 */
    public synchronized void partial(long requestId, String text) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            if ("reviewer".equals(requestSources.get(requestId))) return;
            StringBuilder event = responseHeader(requestId, "未完成回复片段", -1);
            body(event, "已显示文本", text, toolResultMaxChars, false);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void failure(long requestId, Throwable failure, long elapsedMillis) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            requestSources.remove(requestId);
            StringBuilder event = responseHeader(requestId, "模型请求失败", elapsedMillis);
            field(event, "异常类型", failure == null ? "未知" : failure.getClass().getSimpleName());
            body(event, "错误摘要", failure == null ? "未提供错误摘要" : failure.getMessage(), toolResultMaxChars, false);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception ignored) { stop(false); }
    }

    /** 与真实模型请求一同记录请求投影效果；不包含历史正文。 */
    public synchronized void context(dev.codeharness.core.agent.ContextMetrics metrics) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            String event = "\n## 上下文整理\n\n"
                    + "- 本地估算 token：整理前 " + metrics.beforeTokens() + "；整理后 " + metrics.afterTokens()
                    + "；消息预算 " + metrics.budgetTokens() + "。\n"
                    + "- 摘要覆盖消息：" + metrics.summarizedMessages() + "；复用：" + metrics.reused()
                    + "；降级：" + metrics.degraded() + "；耗时：" + metrics.elapsedMillis() + " ms。\n"
                    + "- 原因：" + redactor.text(metrics.reason()) + "。\n"
                    + "- 输入预算已经扣除工具结构；本地估算不等同于厂商计费用量。\n\n";
            append(event);
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void toolStart(int iteration, ToolCall call) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = toolHeader(iteration, "工具开始");
            renderToolCall(event, call);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void toolResult(int iteration, ToolCall call, String result, boolean error) {
        if (disabled || closed) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = toolHeader(iteration, error ? "工具失败" : "工具完成");
            field(event, "工具调用 ID", call.id());
            field(event, "工具", call.name());
            field(event, "状态", error ? "失败" : "成功");
            body(event, "工具结果", result, toolResultMaxChars, false);
            append(event.toString());
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    public synchronized void finish(String status, String error) {
        if (disabled || closed || finished) return;
        try {
            if (!ensureOpen()) return;
            StringBuilder event = new StringBuilder("\n## 任务结束\n\n");
            field(event, "时间（UTC）", Instant.now().toString());
            field(event, "状态", status);
            field(event, "实际请求数", Long.toString(requests));
            if (error != null && !error.isBlank()) body(event, "任务错误", error, toolResultMaxChars, false);
            append(event.toString());
            finished = true;
        } catch (LimitReached limit) { stop(true); }
        catch (Exception failure) { stop(false); }
    }

    /** 尚未产生事件或已禁用时为 null；正常关闭后仍可定位已保存文件。 */
    public synchronized Path path() { return path; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        requestSources.clear();
        references.clear();
        wirePayloads.clear();
        wireOrigins.clear();
        if (output != null) {
            try { output.close(); }
            catch (IOException | RuntimeException ignored) { warn(IO_WARNING); }
            output = null;
        }
    }

    private boolean ensureOpen() {
        if (disabled || closed) return false;
        if (output != null) return true;
        try {
            output = TraceFile.create(workspace, sessionId);
            path = output.path();
            StringBuilder header = new StringBuilder("# YLCoder 执行日志\n\n");
            field(header, "会话 ID", sessionId);
            field(header, "开始时间（UTC）", Instant.now().toString());
            header.append("\n本文记录实际 HTTP 请求正文和解析后的模型回复（已脱敏），以及模型用量、重试和工具过程。请求头不记录；URL 查询参数和用户信息省略。没有 HTTP 观察能力的适配器会明确标为逻辑请求备用记录。\n\n")
                    .append("请求报文的 body 首次直接展示正文，重复内容通过 references 和 content_locations 定位；该占位仅影响日志，不修改发送内容。返回只展示已解析的回答、工具调用、用量和错误，不保存原始响应 JSON/SSE。工具过程摘要最多展示 ").append(toolResultMaxChars)
                    .append(" 字符；请求正文独立保留，捕获上限为 8 MiB，达到上限或流中断会明确标记。回答沿用适配器解析结果，不额外调用模型。\n\n")
                    .append("单个任务文件最大 32 MiB；达到上限会明确停止记录并提示。日志异常不影响任务执行。\n");
            output.append(header.toString());
            return true;
        } catch (IOException | RuntimeException failure) {
            stop(false);
            return false;
        }
    }

    private StringBuilder responseHeader(long id, String title, long elapsedMillis) {
        StringBuilder event = new StringBuilder("\n## ").append(title).append(" #").append(id).append("\n\n");
        event.append("请求：[模型请求 #").append(id).append("](#request-").append(id).append(")\n\n");
        field(event, "时间（UTC）", Instant.now().toString());
        if (elapsedMillis >= 0) field(event, "耗时（毫秒）", Long.toString(elapsedMillis));
        return event;
    }

    private StringBuilder toolHeader(int iteration, String title) {
        StringBuilder event = new StringBuilder("\n## ").append(title).append(" · 事件 ").append(++toolSequence).append("\n\n");
        field(event, "时间（UTC）", Instant.now().toString());
        field(event, "智能体迭代", Integer.toString(iteration));
        return event;
    }

    private void renderToolCall(StringBuilder event, ToolCall call) {
        field(event, "工具调用 ID", call.id());
        field(event, "工具", call.name());
        body(event, "工具参数", call.argumentsJson(), MAX_CONTENT_CHARS, true);
        metadata(event, call.providerMetadata().size());
    }

    private void renderReview(StringBuilder event, ChatResponse response) {
        try {
            if (response.finishReason() != FinishReason.STOP || !response.toolCalls().isEmpty() || response.text().length() > 512)
                throw new IllegalArgumentException("审核格式无效");
            JsonNode parsed = TraceRedactor.JSON.readTree(response.text());
            if (parsed == null || !parsed.isObject() || !parsed.path("decision").isTextual()
                    || !List.of("approve", "manual").contains(parsed.path("decision").asText()))
                throw new IllegalArgumentException("审核格式无效");
            var fields = parsed.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!List.of("decision", "reason").contains(field) || !parsed.get(field).isTextual())
                    throw new IllegalArgumentException("审核格式无效");
            }
            if (parsed.has("reason")) {
                if (parsed.path("reason").textValue().codePointCount(0, parsed.path("reason").textValue().length()) > 80)
                    throw new IllegalArgumentException("审核理由超过长度限制");
            }
            field(event, "审核结论", "approve".equals(parsed.path("decision").asText()) ? "允许" : "需要人工确认");
            if (parsed.has("reason")) field(event, "审核原因", parsed.path("reason").textValue());
        } catch (LimitReached limit) {
            throw limit;
        } catch (Exception invalid) {
            event.append("审核回复结构无效；为避免记录自由推理，未保存原始文本。\n\n");
        }
    }

    private static void metadata(StringBuilder event, int count) {
        if (count > 0) event.append("厂商不透明元数据：省略 ").append(count).append(" 项。\n\n");
    }

    private static void usage(StringBuilder event, TokenUsage usage) {
        event.append("- token：输入 ").append(usage.inputTokens()).append("；输出 ").append(usage.outputTokens())
                .append("；缓存输入 ").append(usage.cachedInputTokens()).append("。\n")
                .append("- 说明：用量为厂商报告值；0 也可能表示厂商未报告。\n\n");
    }

    private void field(StringBuilder event, String label, String value) {
        event.append("- ").append(label).append("：").append(inline(redactor.text(value))).append('\n');
    }

    private void body(StringBuilder event, String label, String value, int limit, boolean arguments) {
        String original = value == null ? "" : value;
        String redacted = arguments ? redactor.arguments(original) : redactor.text(original);
        String key = limit + ":" + digest(redacted);
        String first = references.get(key);
        event.append('\n').append(label).append("：原始 ").append(original.length()).append(" 字符");
        if (original.length() != redacted.length()) event.append("，脱敏后 ").append(redacted.length()).append(" 字符");
        if (first != null) {
            event.append("；重复内容，见 [首次记录](#").append(first).append(")。\n\n");
            return;
        }
        if (references.size() >= MAX_REFERENCES) throw new LimitReached();
        String anchor = "content-" + ++contentSequence;
        references.put(key, anchor);
        int end = Math.min(redacted.length(), limit);
        if (end > 0 && end < redacted.length() && Character.isHighSurrogate(redacted.charAt(end - 1))) end--;
        boolean truncated = end < redacted.length();
        if (truncated) event.append("；已截断，仅展示前 ").append(end).append(" 字符");
        event.append("。\n\n<a id=\"").append(anchor).append("\"></a>\n\n");
        codeBlock(event, redacted.substring(0, end));
        if (truncated) event.append("\n[后续 ").append(redacted.length() - end).append(" 字符未展示]\n\n");
    }

    private static String inline(String text) {
        String value = controls(text).replace("\n", "\\n").replace("\r", "\\r");
        if (value.length() > 512) value = value.substring(0, 512) + "…[已截断]";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace(Character.toString(96), "\\" + Character.toString(96)).replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("|", "\\|");
    }

    private static void codeBlock(StringBuilder event, String text) {
        // 缩进代码块使任意反引号、标题、HTML 和控制序列都只能作为内容展示。
        String safe = controls(text).replace("\r\n", "\n").replace('\r', '\n');
        if (safe.isEmpty()) event.append("    （空）\n");
        else {
            event.append("    ");
            for (int i = 0; i < safe.length(); i++) {
                char c = safe.charAt(i);
                event.append(c);
                if (c == '\n') event.append("    ");
            }
            event.append('\n');
        }
        event.append('\n');
    }

    private static String controls(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) {
                safe.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
            } else safe.append(c);
        }
        return safe.toString();
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 不可用"); }
    }

    private void append(String event) throws IOException {
        if (event.getBytes(StandardCharsets.UTF_8).length > fileLimit - 1_024L - output.size()) throw new LimitReached();
        output.append(event);
    }

    private void stop(boolean limit) {
        if (disabled) return;
        disabled = true;
        if (output != null) {
            if (limit) {
                try { output.append(LIMIT_MARKER); }
                catch (IOException | RuntimeException ignored) { /* 不覆盖原始失败，也不尝试其他路径。 */ }
            }
            try { output.close(); }
            catch (IOException | RuntimeException ignored) { /* 记录器失效不会影响主任务。 */ }
            output = null;
        }
        references.clear();
        requestSources.clear();
        warn(limit ? LIMIT_WARNING : IO_WARNING);
    }

    private void warn(String message) {
        if (warned) return;
        warned = true;
        notifySafely(warning, message);
    }

    private static void notifySafely(Consumer<String> callback, String message) {
        if (callback == null) return;
        try { callback.accept(message); }
        catch (RuntimeException ignored) { /* 提示通道本身失效也不能中断任务。 */ }
    }

    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
