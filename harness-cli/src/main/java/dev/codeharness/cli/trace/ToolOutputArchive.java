package dev.codeharness.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeharness.core.agent.ToolOutputStore;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 大工具结果先脱敏后按内容去重存档；模型仅收到摘要和只读检索标识。 */
public final class ToolOutputArchive implements ToolOutputStore {
    private static final int MAX_ARCHIVE_BYTES = 4_000_000;
    private static final int MAX_PAGE_CHARS = 8_000;
    private static final long MAX_TOTAL_BYTES = 64_000_000;
    private final Path root;
    private final TraceRedactor redactor;
    // 只识别本实例生成的投影，避免把工具自己伪造的存档提示当成可信来源。
    private final Map<String, ProjectionReference> projections = new LinkedHashMap<>(32, .75f, true);
    private record ProjectionReference(String archiveId, String toolName, String hint) {}

    public ToolOutputArchive(Path workspace, Collection<String> knownSecrets) {
        root = workspace.toAbsolutePath().normalize();
        redactor = new TraceRedactor(knownSecrets);
    }

    @Override public synchronized String store(String toolName, String output, int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("工具输出上限必须为正数");
        String clean = redactor.text(output);
        if (clean.length() <= maxChars) return clean;
        byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
        ProjectionReference previous = projections.get(hash(bytes));
        if (previous != null) return reproject(clean, previous, maxChars);
        String normalizedTool = toolName == null ? "" : toolName;
        String archiveId = null;
        String hint;
        try {
            if (bytes.length > MAX_ARCHIVE_BYTES) throw new IOException("输出超过单份存档 4 MB 上限");
            String id = hash(bytes);
            Path directory = prepare();
            Map<Path, Object> identities = directories(directory);
            Path path = directory.resolve(id + ".txt");
            if (!java.nio.file.Files.exists(path, LinkOption.NOFOLLOW_LINKS)) checkQuota(directory, bytes.length);
            try (FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS), TraceFile.attributes(directory, false))) {
                TraceFile.restrict(path, false);
                Object identity = TraceFile.identity(path, false);
                verifyDirectories(identities);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(false);
                verifyDirectories(identities);
                if (!identity.equals(TraceFile.identity(path, false))) throw new IOException("存档文件被替换");
            } catch (FileAlreadyExistsException duplicate) {
                // 已有同名内容仍验证身份和摘要，绝不覆写未知文件。
                if (!hash(readBytes(path, identities)).equals(id)) throw new IOException("同名存档内容不一致");
            }
            archiveId = id;
            hint = "[工具原文已脱敏存档] archive_id=" + id + "；字符=" + clean.length()
                    + "；按需调用 read_tool_output(archive_id, start_line, end_line) 读取原文。\n";
        } catch (IOException | SecurityException failure) {
            hint = "[工具原文存档不可用] 原因：" + safeFailure(failure) + "；当前只保留摘要。\n";
        }
        return remember(projection(clean, hint, normalizedTool, maxChars),
                new ProjectionReference(archiveId, normalizedTool, hint));
    }

    /** 工作区工具和代理可能先后收窄预算；二次投影继续指向同一份完整原文。 */
    private String reproject(String previousText, ProjectionReference reference, int maxChars) {
        int firstLineEnd = previousText.indexOf('\n');
        String text = firstLineEnd < 0 ? "" : previousText.substring(firstLineEnd + 1);
        String hint = reference.hint();
        ProjectionReference resultReference = reference;
        if (reference.archiveId() != null) {
            try {
                Path directory = root.resolve(".harness").resolve("artifacts");
                byte[] original = readBytes(directory.resolve(reference.archiveId() + ".txt"), directories(directory));
                if (!hash(original).equals(reference.archiveId())) throw new IOException("存档内容与标识不一致");
                text = redactor.text(new String(original, StandardCharsets.UTF_8));
            } catch (IOException | SecurityException failure) {
                hint = "[工具原文存档不可用] 原因：" + safeFailure(failure) + "；当前只保留摘要。\n";
                resultReference = new ProjectionReference(null, reference.toolName(), hint);
            }
        }
        return remember(projection(text, hint, reference.toolName(), maxChars), resultReference);
    }

    private String remember(String text, ProjectionReference reference) {
        projections.put(hash(text.getBytes(StandardCharsets.UTF_8)), reference);
        // 只存摘要指纹和原文编号，不把大结果留在内存，也不让长期会话无限增长。
        if (projections.size() > 1024) projections.remove(projections.keySet().iterator().next());
        return text;
    }

    public Tool readerTool() {
        return new Tool() {
            @Override public boolean isParallelSafeRead() { return true; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition("read_tool_output", "分页读取已脱敏的工具原文存档；不执行命令，不重放工具。", Map.of(
                        "type", "object", "properties", Map.of(
                        "archive_id", Map.of("type", "string", "description", "摘要中的 64 位 SHA-256 标识"),
                        "start_line", Map.of("type", "integer", "description", "起始行，默认 1"),
                        "end_line", Map.of("type", "integer", "description", "结束行，默认起始行加 199；每页最多 500 行"),
                        "start_char", Map.of("type", "integer", "description", "起始行内的字符偏移，默认 0；用于读取超长单行")),
                        "required", List.of("archive_id"), "additionalProperties", false));
            }
            @Override public String execute(String argumentsJson) throws Exception {
                JsonNode input = TraceRedactor.JSON.readTree(argumentsJson);
                if (input == null || !input.isObject()) throw new IllegalArgumentException("存档读取参数必须是对象");
                var fields = input.fieldNames();
                while (fields.hasNext()) if (!Set.of("archive_id", "start_line", "end_line", "start_char").contains(fields.next()))
                    throw new IllegalArgumentException("存档读取包含未知字段");
                JsonNode value = input.get("archive_id");
                if (value == null || !value.isTextual() || !value.textValue().matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("存档标识必须为 64 位小写 SHA-256");
                String id = value.textValue();
                int start = integer(input, "start_line", 1);
                int startChar = integer(input, "start_char", 0);
                int end = integer(input, "end_line", (int) Math.min(Integer.MAX_VALUE, (long) start + 199));
                if (start < 1 || startChar < 0 || end < start || (long) end - start >= 500)
                    throw new IllegalArgumentException("存档读取行范围无效；每页最多 500 行");
                Path directory = root.resolve(".harness").resolve("artifacts");
                Map<Path, Object> identities = directories(directory);
                byte[] bytes = readBytes(directory.resolve(id + ".txt"), identities);
                if (!hash(bytes).equals(id)) throw new IOException("存档内容与标识不一致");
                String text;
                synchronized (ToolOutputArchive.this) { text = redactor.text(new String(bytes, StandardCharsets.UTF_8)); }
                List<String> lines = text.lines().toList();
                StringBuilder result = new StringBuilder("[工具原文分页] archive_id=" + id + "；共 " + lines.size() + " 行\n");
                if (start > lines.size()) return result.append("已到末尾，没有更多内容。\n").toString();
                int next = start;
                for (int line = start; line <= Math.min(end, lines.size()); line++) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("存档读取已取消");
                    String original = lines.get(line - 1);
                    int offset = line == start ? startChar : 0;
                    if (offset > original.length()) throw new IllegalArgumentException("行内字符偏移超过本行长度");
                    if (offset > 0 && offset < original.length() && Character.isLowSurrogate(original.charAt(offset))
                            && Character.isHighSurrogate(original.charAt(offset - 1))) offset--;
                    String prefix = line + ": ";
                    String content = original.substring(offset);
                    int available = MAX_PAGE_CHARS - result.length() - 180 - prefix.length();
                    if (content.length() + 1 > available) {
                        if (line != start) break;
                        String segment = clip(content, Math.max(0, available));
                        result.append(prefix).append(segment).append("\n[后续内容] 下次 start_line=").append(line)
                                .append("；start_char=").append(offset + segment.length()).append("\n");
                        return result.toString();
                    }
                    result.append(prefix).append(content).append('\n');
                    next = line + 1;
                }
                if (next <= lines.size()) result.append("[后续内容] 下次 start_line=").append(next).append("\n");
                return result.toString();
            }
        };
    }

    private static void checkQuota(Path directory, int bytes) throws IOException {
        long total = bytes;
        int count = 0;
        try (var files = java.nio.file.Files.newDirectoryStream(directory, "*.txt")) {
            for (Path file : files) {
                TraceFile.identity(file, false);
                total += java.nio.file.Files.size(file);
                if (++count >= 512 || total > MAX_TOTAL_BYTES) throw new IOException("工具存档已达容量上限；请清理本地 artifacts 后重试");
            }
        }
    }

    private Path prepare() throws IOException {
        TraceFile.verifyDirectory(root);
        Path state = root.resolve(".harness");
        TraceFile.ensureDirectory(state, false);
        Path directory = state.resolve("artifacts");
        TraceFile.ensureDirectory(directory, true);
        TraceFile.ensureIgnoreFile(directory);
        return directory;
    }

    private Map<Path, Object> directories(Path directory) throws IOException {
        Map<Path, Object> identities = new LinkedHashMap<>();
        for (Path path : List.of(root, root.resolve(".harness"), directory)) {
            TraceFile.verifyDirectory(path);
            identities.put(path, TraceFile.identity(path, true));
        }
        return identities;
    }

    private static void verifyDirectories(Map<Path, Object> identities) throws IOException {
        for (var entry : identities.entrySet()) {
            TraceFile.verifyDirectory(entry.getKey());
            if (!entry.getValue().equals(TraceFile.identity(entry.getKey(), true))) throw new IOException("存档目录被替换");
        }
    }

    private static byte[] readBytes(Path path, Map<Path, Object> directories) throws IOException {
        verifyDirectories(directories);
        Object identity = TraceFile.identity(path, false);
        try (FileChannel input = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (input.size() > MAX_ARCHIVE_BYTES) throw new IOException("存档超过读取上限");
            ByteBuffer buffer = ByteBuffer.allocate((int) input.size());
            while (buffer.hasRemaining()) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("存档读取已取消");
                if (input.read(buffer) < 0) throw new IOException("存档在读取期间发生变化");
            }
            verifyDirectories(directories);
            if (!identity.equals(TraceFile.identity(path, false)) || input.size() != buffer.capacity())
                throw new IOException("存档文件被替换或修改");
            return buffer.array();
        }
    }

    private static int integer(JsonNode input, String field, int fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(field + " 必须是整数");
        return value.intValue();
    }

    private static String projection(String text, String hint, String tool, int limit) {
        if (hint.length() >= limit) return clip(hint, limit);
        int available = limit - hint.length();
        StringBuilder important = new StringBuilder();
        if (tool.equals("run_command") || tool.equals("mcp") || tool.contains("test")) {
            for (String line : text.lines().toList()) {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("error") || lower.contains("failed") || lower.contains("failure")
                        || lower.contains("exception") || line.contains("失败") || line.contains("错误")) {
                    String selected = clip(line, 400) + "\n";
                    if (important.length() + selected.length() > available / 3) break;
                    important.append(selected);
                }
            }
        }
        String separator = important.isEmpty() ? "" : "[关键错误]\n" + important;
        int remaining = Math.max(0, available - separator.length());
        if (text.length() <= remaining) return hint + separator + text;
        String marker = "\n… [正文已摘要，原文可检索] …\n";
        if (remaining <= marker.length()) return clip(hint + separator + text, limit);
        int head = (remaining - marker.length()) / 2;
        int tail = remaining - marker.length() - head;
        int end = text.length() - tail;
        if (end < text.length() && end > 0 && Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) end++;
        return hint + separator + clip(text, head) + marker + text.substring(end);
    }

    private static String clip(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }

    private static String safeFailure(Exception failure) {
        String message = failure.getMessage();
        return message != null && !message.contains(":\\") && !message.contains("/") && message.length() <= 100
                ? message : "无法安全创建或核对本地存档";
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
