package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 使用路径与声明构建小型仓库地图；不把全部文件正文塞进模型上下文。 */
public final class RepositoryMap implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> EXCLUDED = Set.of(".git", ".harness", ".idea", ".codex", ".aws", "target", "build", "dist", "node_modules", "__pycache__");
    private static final Set<String> EXTENSIONS = Set.of("java", "kt", "py", "js", "ts", "tsx", "jsx", "go", "rs", "cs", "cpp", "c", "h");
    private static final Pattern DECLARATION = Pattern.compile("\\b(?:class|interface|enum|record|def|function|struct|trait)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final int CACHE_LIMIT = 2048;
    private final Path root;
    private final Map<Path, Cached> cache = new HashMap<>();
    public RepositoryMap(Path workspace) throws IOException { root = workspace.toRealPath(); }
    @Override public boolean isParallelSafeRead() { return true; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition("repository_map", "获取相关源码路径与主要类型/函数声明，先用地图定位，再用 read_file 分段读取。query 为空时返回小型概览。",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"),
                        "max_files", Map.of("type", "integer", "minimum", 1, "maximum", 100)),
                        "additionalProperties", false));
    }
    @Override public synchronized String execute(String input) throws Exception {
        var node = JSON.readTree(input);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("地图参数必须为对象");
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!Set.of("query", "max_files").contains(fields.next()))
            throw new IllegalArgumentException("地图参数包含未知字段");
        if (node.has("query") && !node.get("query").isTextual()) throw new IllegalArgumentException("query 必须为字符串");
        String query = node.path("query").asText("").toLowerCase(Locale.ROOT);
        int max = 40;
        if (node.has("max_files")) {
            var value = node.get("max_files");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("max_files 必须为整数");
            max = value.intValue();
        }
        if (max < 1 || max > 100 || query.length() > 512) throw new IllegalArgumentException("地图参数超限");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IOException("工作区不能通过链接或 junction 重定向");
        List<Entry> candidates = new ArrayList<>();
        int[] visited = {0};
        boolean[] incomplete = {false};
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        Files.walkFileTree(root, Set.of(), 12, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                interrupted();
                if (System.nanoTime() > deadline || ++visited[0] > 10000) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                if (!dir.equals(root) && EXCLUDED.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.SKIP_SUBTREE;
                return safe(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                interrupted();
                if (System.nanoTime() > deadline || ++visited[0] > 10000) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (!attrs.isRegularFile() || attrs.size() > 256_000 || dot < 0
                        || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)) || !safe(file)) return FileVisitResult.CONTINUE;
                String path = root.relativize(file).toString().replace('\\', '/');
                var symbols = symbols(file, attrs, deadline);
                if (System.nanoTime() > deadline) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                boolean pathMatch = !query.isBlank() && path.toLowerCase(Locale.ROOT).contains(query);
                boolean symbolMatch = !query.isBlank() && symbols.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(query));
                if (query.isBlank() || pathMatch || symbolMatch) candidates.add(new Entry(path, symbols, pathMatch ? 2 : symbolMatch ? 1 : 0));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                interrupted(); incomplete[0] = true; return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                interrupted(); if (error != null) incomplete[0] = true; return FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(java.util.Comparator.comparingInt(Entry::rank).reversed().thenComparing(Entry::path));
        StringBuilder result = new StringBuilder();
        int shown = 0;
        for (Entry entry : candidates) {
            if (shown == max || result.length() > 7500) break;
            String row = entry.path + (entry.symbols.isEmpty() ? "" : " — " + String.join(", ", entry.symbols)) + "\n";
            if (result.length() + row.length() > 8000) break;
            result.append(row); shown++;
        }
        if (candidates.isEmpty()) result.append("未找到相关源码声明。\n");
        result.append("[地图仅含路径与声明，不替代正文；展示 ").append(shown).append('/').append(candidates.size())
                .append("；扫描文件 ").append(visited[0]).append(incomplete[0] ? "；扫描达到预算或存在不可读路径" : "").append("]");
        return result.toString();
    }
    private List<String> symbols(Path file, BasicFileAttributes attrs, long deadline) throws IOException {
        Cached previous = cache.get(file);
        if (previous != null && previous.size == attrs.size() && previous.modified.equals(attrs.lastModifiedTime())
                && java.util.Objects.equals(previous.fileKey, attrs.fileKey())) return previous.symbols;
        var found = new ArrayList<String>();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) return List.of();
        byte[] bytes;
        try (var input = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() > 256_000) return List.of();
            var buffer = java.nio.ByteBuffer.allocate((int) input.size());
            while (buffer.hasRemaining()) {
                interrupted();
                if (System.nanoTime() > deadline) return List.of();
                if (input.read(buffer) < 0) throw new IOException("源码在地图读取期间变化");
            }
            var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!safe(file) || !java.util.Objects.equals(attrs.fileKey(), after.fileKey())
                    || attrs.size() != after.size() || !attrs.lastModifiedTime().equals(after.lastModifiedTime()))
                return List.of();
            bytes = buffer.array();
        }
        String text;
        try { text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException ignored) { return List.of(); }
        int number = 0;
        for (String line : text.lines().toList()) {
            interrupted();
            if (++number > 4000 || found.size() >= 8 || System.nanoTime() > deadline) break;
            if (line.indexOf('\0') >= 0) return List.of();
            var matcher = DECLARATION.matcher(line);
            if (matcher.find()) found.add(matcher.group(1) + ":" + number);
        }
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        var symbols = List.copyOf(found);
        cache.put(file, new Cached(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), symbols));
        return symbols;
    }
    private boolean safe(Path path) throws IOException {
        return !Files.isSymbolicLink(path) && path.toRealPath().equals(path) && path.toRealPath().startsWith(root);
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("仓库地图已中断");
    }
    private record Entry(String path, List<String> symbols, int rank) { }
    private record Cached(long size, java.nio.file.attribute.FileTime modified, Object fileKey, List<String> symbols) { }
}
