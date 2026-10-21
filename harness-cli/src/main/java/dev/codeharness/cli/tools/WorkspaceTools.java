package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 限制在工作区内的文件工具。shell 命令需要批准，且不构成操作系统沙箱。 */
public final class WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".harness", "target", "node_modules", "build", "dist");
    private static final int RESULT_LIMIT = 100;
    private static final int OUTPUT_LIMIT = 11_000;
    private final Path root;
    private final CommandApproval approval;
    private final Duration commandTimeout;
    private final ChangeTracker changes = new ChangeTracker();

    private WorkspaceTools(Path workspace, CommandApproval approval, Duration commandTimeout) throws Exception {
        Files.createDirectories(workspace);
        this.root = workspace.toRealPath();
        this.approval = Objects.requireNonNull(approval, "approval");
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) throw new IllegalArgumentException("commandTimeout 必须为正数");
    }

    public static ToolRegistry create(Path workspace, Predicate<String> approval) throws Exception {
        return create(workspace, approval, Duration.ofMinutes(2));
    }

    static ToolRegistry create(Path workspace, Predicate<String> approval, Duration timeout) throws Exception {
        Objects.requireNonNull(approval, "approval");
        return createWithCommandApproval(workspace, request -> approval.test(
                "批准 shell 命令（拥有不受限制的文件系统和网络访问权限）吗？\n  " + request.command())
                ? CommandApproval.Decision.shell() : CommandApproval.Decision.denied(), timeout);
    }

    public static ToolRegistry createWithCommandApproval(Path workspace, CommandApproval approval) throws Exception {
        return createWithCommandApproval(workspace, approval, Duration.ofMinutes(2));
    }

    private static ToolRegistry createWithCommandApproval(Path workspace, CommandApproval approval, Duration timeout) throws Exception {
        WorkspaceTools tools = new WorkspaceTools(workspace, approval, timeout);
        return new ToolRegistry(List.of(tools.new ListFiles(), tools.new SearchFiles(), tools.new ReadFile(),
                tools.new WriteFile(), tools.new EditFile(), tools.new Changes(), tools.new RunCommand()));
    }

    private Path resolve(String relative, boolean allowMissing) throws Exception {
        Path supplied = Path.of(relative == null || relative.isBlank() ? "." : relative);
        Path resolved = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
        if (!resolved.startsWith(root)) throw new SecurityException("路径位于工作区之外");
        Path existing = resolved;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) throw new SecurityException("路径没有已存在的父目录");
        Path canonical = existing.toRealPath();
        if (!canonical.startsWith(root)) throw new SecurityException("路径解析到了工作区之外");
        if (hasSymlinkComponent(resolved)) throw new SecurityException("路径包含符号链接组件");
        if (!allowMissing && !Files.exists(resolved)) throw new java.nio.file.NoSuchFileException(relative);
        if (Files.isSymbolicLink(resolved)) {
            Path target = resolved.toRealPath();
            if (!target.startsWith(root)) throw new SecurityException("符号链接目标位于工作区之外");
        }
        return resolved;
    }

    private boolean hasSymlinkComponent(Path path) {
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return true;
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
        }
        return false;
    }

    private JsonNode args(String input) throws Exception {
        JsonNode node = JSON.readTree(input);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        return node;
    }
    private String str(JsonNode node, String name, String fallback) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " 必须是字符串");
        return value.textValue();
    }
    private String required(JsonNode node, String name) {
        if (!node.hasNonNull(name)) throw new IllegalArgumentException(name + " 为必填项");
        return str(node, name, "");
    }
    private ToolDefinition definition(String name, String description, Map<String, Object> properties, String... required) {
        return new ToolDefinition(name, description, Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false));
    }
    private Map<String, Object> stringProp(String description) { return Map.of("type", "string", "description", description); }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void preservePermissions(Path source, Path target) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException ignored) {
            // DOS/ACL 文件系统提供程序不暴露 POSIX 权限；原子替换保留提供程序的默认设置。
        }
    }

    private void atomicReplace(Path file, byte[] content) throws IOException {
        if (Files.isSymbolicLink(file)) throw new SecurityException("不支持通过符号链接写入");
        Path temporary = Files.createTempFile(file.getParent(), ".harness-write-", ".tmp");
        try {
            Files.write(temporary, content);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) preservePermissions(file, temporary);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] encodeText(String text) throws CharacterCodingException {
        if (text.length() > 1_000_000) throw new IllegalArgumentException("文件内容超过 1 MB");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length > 1_000_000) throw new IllegalArgumentException("文件内容超过 1 MB");
        return bytes;
    }

    private static byte[] readLimited(Path file) throws IOException {
        return BoundedFileRead.read(file);
    }

    private static List<String> readTextLines(Path file) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(readLimited(file))).toString();
        return text.lines().toList();
    }

    private static int lineNumber(JsonNode node, String name, int fallback) {
        JsonNode value = node.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " 必须是 32 位整数");
        }
        return value.intValue();
    }

    private String replaceAndReport(Path file, byte[] before, byte[] after) throws IOException {
        if (Arrays.equals(before, after)) return "无变更：" + UnifiedDiff.quoted(display(file)) + "\n";
        changes.checkCapacity(file, before);
        atomicReplace(file, after);
        changes.committed(file, before);
        return UnifiedDiff.render(display(file), before, after, 4_000);
    }

    private final class ListFiles implements Tool {
        @Override public boolean isParallelSafeRead() { return true; }
        public ToolDefinition definition() { return WorkspaceTools.this.definition("list_files", "列出项目文件，可选择仅列出某个相对目录下的文件。", Map.of("path", stringProp("相对于工作区的目录"))); }
        public String execute(String input) throws Exception {
            checkReadInterrupted();
            Path dir = resolve(str(args(input), "path", "."), false);
            if (!Files.isDirectory(dir)) throw new IllegalArgumentException("路径不是目录");
            BoundedOutput output = new BoundedOutput();
            Files.walkFileTree(dir, Set.of(), 5, new SimpleFileVisitor<>() {
                private int count;
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    checkReadInterrupted();
                    if (!safeTraversal(path)) return FileVisitResult.SKIP_SUBTREE;
                    return path.equals(dir) ? FileVisitResult.CONTINUE : add(path, true);
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    checkReadInterrupted();
                    return safeTraversal(path) ? add(path, attributes.isDirectory()) : FileVisitResult.CONTINUE;
                }
                private FileVisitResult add(Path path, boolean directory) {
                    output.append(display(path) + (directory ? "/" : "") + "\n");
                    return ++count >= RESULT_LIMIT || output.truncated() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException { checkReadInterrupted(); return FileVisitResult.CONTINUE; }
            });
            return output.text();
        }
    }

    private final class SearchFiles implements Tool {
        @Override public boolean isParallelSafeRead() { return true; }
        public ToolDefinition definition() { return WorkspaceTools.this.definition("search_files", "在文本文件中搜索字面文本，并返回匹配的文件行。", Map.of("query", stringProp("要查找的字面文本"), "path", stringProp("可选的工作区相对目录")), "query"); }
        public String execute(String input) throws Exception {
            checkReadInterrupted();
            JsonNode node = args(input);
            String query = required(node, "query");
            if (query.isEmpty()) throw new IllegalArgumentException("query 不能为空");
            Path dir = resolve(str(node, "path", "."), false);
            BoundedOutput output = new BoundedOutput();
            Files.walkFileTree(dir, Set.of(), 8, new SimpleFileVisitor<>() {
                private int matches;
                private int scanned;
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    checkReadInterrupted();
                    return safeTraversal(path) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    checkReadInterrupted();
                    if (!attributes.isRegularFile() || !safeTraversal(file) || attributes.size() > 1_000_000) return FileVisitResult.CONTINUE;
                    if (++scanned > 10_000) return FileVisitResult.TERMINATE;
                    List<String> lines;
                    try { lines = readTextLines(file); }
                    catch (IOException | IllegalArgumentException e) { checkReadInterrupted(); return FileVisitResult.CONTINUE; }
                    if (lines.stream().anyMatch(line -> line.indexOf('\0') >= 0)) return FileVisitResult.CONTINUE;
                    for (int i = 0; i < lines.size(); i++) {
                        checkReadInterrupted();
                        if (!lines.get(i).contains(query)) continue;
                        output.append(display(file) + ":" + (i + 1) + ": " + lines.get(i) + "\n");
                        if (++matches >= RESULT_LIMIT || output.truncated()) return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException { checkReadInterrupted(); return FileVisitResult.CONTINUE; }
            });
            return output.text();
        }
    }

    private final class ReadFile implements Tool {
        @Override public boolean isParallelSafeRead() { return true; }
        public ToolDefinition definition() { return WorkspaceTools.this.definition("read_file", "读取文本文件，可指定行范围。", Map.of("path", stringProp("相对于工作区的文件"), "start_line", Map.of("type", "integer"), "end_line", Map.of("type", "integer")), "path"); }
        public String execute(String input) throws Exception {
            checkReadInterrupted();
            JsonNode node = args(input);
            int start = lineNumber(node, "start_line", 1);
            int end = lineNumber(node, "end_line", (int) Math.min(Integer.MAX_VALUE, (long) start + 299));
            if (start < 1 || end < start) throw new IllegalArgumentException("行范围必须为正数且起止有序");
            Path file = resolve(required(node, "path"), false);
            if (!Files.isRegularFile(file) || Files.size(file) > 1_000_000) throw new IllegalArgumentException("文件必须是可读的普通文件，且小于 1 MB");
            List<String> lines = readTextLines(file);
            checkReadInterrupted();
            BoundedOutput output = new BoundedOutput();
            for (int i = start; i <= Math.min(end, lines.size()); i++) {
                checkReadInterrupted();
                output.append(i + ": " + lines.get(i - 1) + "\n");
                if (output.truncated()) break;
            }
            return output.text();
        }
    }

    private static void checkReadInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("工作区读取已中断");
    }

    private final class WriteFile implements Tool {
        public ToolDefinition definition() { return WorkspaceTools.this.definition("write_file", "在工作区内创建或替换 UTF-8 文本文件，并报告大小受限且带上下文的差异。", Map.of("path", stringProp("相对于工作区的文件"), "content", stringProp("完整文件内容")), "path", "content"); }
        public String execute(String input) throws Exception {
            JsonNode node = args(input);
            String name = required(node, "path");
            byte[] content = encodeText(required(node, "content"));
            Path file = resolve(name, true);
            if (file.equals(root) || Files.isDirectory(file)) throw new IllegalArgumentException("path 必须指向文件");
            if (Files.isSymbolicLink(file)) throw new SecurityException("不支持通过符号链接写入");
            synchronized (changes) {
                byte[] before = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? readLimited(file) : null;
                if (!Arrays.equals(before, content)) changes.checkCapacity(file, before);
                Files.createDirectories(file.getParent());
                resolve(name, true);
                String diff = replaceAndReport(file, before, content);
                return (Arrays.equals(before, content) ? "未修改 " : "已写入 ") + UnifiedDiff.quoted(display(file))
                        + " (" + content.length + " 字节)\n" + diff;
            }
        }
    }

    private final class EditFile implements Tool {
        public ToolDefinition definition() {
            return WorkspaceTools.this.definition("edit_file", "精确替换 UTF-8 文本中一处字面匹配，可使用当前 SHA-256 保护，并报告大小受限且带上下文的差异。",
                    Map.of("path", stringProp("相对于工作区的文件"), "old_text", stringProp("要替换的精确字面文本"),
                            "new_text", stringProp("替换后的文本"), "expected_sha256", stringProp("可选的当前文件字节的小写 SHA-256")),
                    "path", "old_text", "new_text");
        }

        public String execute(String input) throws Exception {
            JsonNode node = args(input);
            Path file = resolve(required(node, "path"), false);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 1_000_000) {
                throw new SecurityException("edit_file 要求文件为小于 1 MB 的普通 UTF-8 文件，且不能是符号链接");
            }
            byte[] before = readLimited(file);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(before)).toString();
            String expected = str(node, "expected_sha256", "").trim().toLowerCase(Locale.ROOT);
            if (node.hasNonNull("expected_sha256") && !expected.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected_sha256 必须包含 64 个十六进制字符");
            }
            String actual = sha256(before);
            if (!expected.isEmpty() && !expected.equals(actual)) throw new IllegalStateException("文件已变化（expected_sha256 与当前内容不匹配）");
            String oldText = required(node, "old_text");
            String newText = required(node, "new_text");
            encodeText(oldText);
            encodeText(newText);
            if (oldText.isEmpty()) throw new IllegalArgumentException("old_text 不能为空");
            int first = text.indexOf(oldText);
            if (first < 0) throw new IllegalStateException("未找到 old_text");
            if (text.indexOf(oldText, first + 1) >= 0) throw new IllegalStateException("old_text 匹配超过一处");
            String updated = text.substring(0, first) + newText + text.substring(first + oldText.length());
            byte[] after = encodeText(updated);
            // 替换前立即重新检查路径和内容，拒绝基于过期内容的并发编辑。
            synchronized (changes) {
                Path checked = resolve(required(node, "path"), false);
                if (Files.isSymbolicLink(checked)) throw new SecurityException("不支持通过符号链接写入");
                byte[] current = readLimited(checked);
                if (!sha256(current).equals(actual)) throw new IllegalStateException("编辑期间文件已变化；请读取最新内容后重试");
                return (Arrays.equals(before, after) ? "未修改 " : "已编辑 ") + UnifiedDiff.quoted(display(checked))
                        + (Arrays.equals(before, after) ? ": sha256=" : ": 已替换 1 处字面匹配；sha256=") + sha256(after)
                        + "\n" + replaceAndReport(checked, before, after);
            }
        }
    }

    private final class Changes implements Tool {
        public ToolDefinition definition() {
            return WorkspaceTools.this.definition("changes", "报告当前工作区工具注册表成功写入或编辑路径的净变更，输出大小受限。恢复会话时不会保留这些基线。", Map.of());
        }
        public String execute(String input) throws Exception {
            args(input);
            synchronized (changes) {
                BoundedOutput output = new BoundedOutput();
                int changed = 0;
                for (var entry : changes.originals().entrySet()) {
                    Path file = resolve(display(entry.getKey()), true);
                    byte[] before = entry.getValue().bytes();
                    byte[] after = null;
                    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(file)) throw new SecurityException("已跟踪的路径不再是普通文件");
                        if (Files.size(file) > 1_000_000) {
                            output.append("已修改 " + UnifiedDiff.quoted(display(file)) + ": 当前文件超过 1 MB；无法提供差异\n");
                            changed++;
                            if (output.truncated()) break;
                            continue;
                        }
                        after = readLimited(file);
                    }
                    if (Arrays.equals(before, after)) continue;
                    changed++;
                    output.append((before == null ? "已创建 " : after == null ? "已删除 " : "已修改 ")
                            + UnifiedDiff.quoted(display(file)) + "\n");
                    output.append(UnifiedDiff.render(display(file), before, after, OUTPUT_LIMIT));
                    if (output.truncated()) break;
                }
                return changed == 0 ? "当前工作区工具注册表中没有净变更。" : output.text();
            }
        }
    }

    private final class RunCommand implements Tool {
        public ToolDefinition definition() { return WorkspaceTools.this.definition("run_command", "在工作区中执行命令。目录/时间只读查询及安全 Git 检查可由 AI 审核子 agent 审核后直接执行；未知或有副作用的命令仍需确认。设有超时和输出限制，不是操作系统沙箱。", Map.of("command", stringProp("要执行的命令行")), "command"); }
        public String execute(String input) throws Exception {
            String command = required(args(input), "command").trim();
            if (command.isEmpty()) throw new IllegalArgumentException("command 不能为空");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("命令在审批前被中断");
            CommandApproval.Decision decision = approval.approve(new CommandApproval.Request(root, command));
            if (decision == null || !decision.approved()) {
                throw new SecurityException("命令未获批准");
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            // /s 会移除最外层引号。显式提供一层包裹，使带引号的可执行文件
            // 和最后一个参数的路径在 shell 命令中保留各自的引号。
            ProcessBuilder builder = !decision.arguments().isEmpty() ? new ProcessBuilder(decision.arguments()) : windows
                    ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"" + command + "\"")
                    : new ProcessBuilder("/bin/sh", "-c", command);
            builder.directory(root.toFile());
            if (!decision.arguments().isEmpty()) {
                // 受限执行计划不继承可改写 Git 位置/配置或自动加载 Java 代码的环境入口。
                builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_")
                        || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                        "DIRCMD", "RIPGREP_CONFIG_PATH")
                        .contains(name.toUpperCase(Locale.ROOT)));
                builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                builder.environment().put("GIT_CONFIG_GLOBAL", windows ? "NUL" : "/dev/null");
                builder.environment().put("GIT_CONFIG_SYSTEM", windows ? "NUL" : "/dev/null");
                builder.environment().put("GIT_NO_LAZY_FETCH", "1");
            }
            builder.redirectErrorStream(true);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("命令在启动前被中断");
            Process process = builder.start();
            BoundedOutput output = new BoundedOutput();
            Thread drain = Thread.ofVirtual().name("harness-command-output").unstarted(() -> {
                try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) output.append(new String(buffer, 0, count));
                } catch (IOException ignored) {
                    // 超时或中断时关闭进程管道属于正常情况。
                }
            });
            boolean finished = false;
            boolean cleanupStarted = false;
            try {
                drain.start();
                Thread.ofPlatform().daemon(true).name("harness-command-stdin").start(() -> {
                    try { process.getOutputStream().close(); } catch (IOException ignored) { }
                });
                finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    cleanupStarted = true;
                    CommandProcessCleanup.stop(process);
                }
                drain.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                if (!finished && !cleanupStarted) CommandProcessCleanup.stop(process);
                else if (finished) CommandProcessCleanup.closePipes(process);
                drain.interrupt();
            }
            String status = finished ? "退出码：" + process.exitValue() : "命令超时，已等待 " + commandTimeout.toMillis() + " ms";
            return status + "\n" + output.text();
        }
    }

    private boolean safeTraversal(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return false;
        for (Path part : root.relativize(path)) if (EXCLUDED.contains(part.toString().toLowerCase(Locale.ROOT))) return false;
        return path.toRealPath().startsWith(root);
    }

    private String display(Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private static final class BoundedOutput {
        private final StringBuilder content = new StringBuilder();
        private boolean truncated;

        synchronized void append(String value) {
            int remaining = OUTPUT_LIMIT - content.length();
            int take = Math.min(remaining, value.length());
            content.append(value, 0, take);
            if (take < value.length()) truncated = true;
        }

        synchronized boolean truncated() { return truncated; }
        synchronized String text() { return content + (truncated ? "\n[输出已截断]" : ""); }
    }
}
