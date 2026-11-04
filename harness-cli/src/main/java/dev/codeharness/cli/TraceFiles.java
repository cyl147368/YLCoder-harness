package dev.codeharness.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** 只定位已有日志，不加载配置、创建目录或跟随符号链接与 junction。 */
final class TraceFiles {
    private TraceFiles() { }
    private static final String SESSION = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}";
    private static final int MAX_ENTRIES = 100_000;

    static Path directory(Path workspace) throws IOException { return workspace.toRealPath().resolve(".harness/logs"); }

    static List<Path> recent(Path workspace, String session, int limit) throws IOException {
        if (session != null && !session.matches(SESSION)) throw new IOException("会话 ID 无效");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("日志列表数量必须为 1..100");
        Path root = workspace.toRealPath();
        verify(root);
        Path state = root.resolve(".harness");
        if (!exists(state)) return List.of();
        verify(state);
        Path logs = state.resolve("logs");
        if (!exists(logs)) return List.of();
        verify(logs);
        Comparator<Entry> order = Comparator.comparingLong(Entry::modified).thenComparing(entry -> entry.path.toString());
        var recent = new PriorityQueue<Entry>(limit, order);
        int[] inspected = {0};
        if (session != null) {
            Path selected = logs.resolve(session);
            if (!exists(selected)) return List.of();
            verify(selected);
            collect(selected, recent, limit, order, inspected);
        } else {
            try (var sessions = Files.newDirectoryStream(logs)) {
                for (Path selected : sessions) {
                    count(inspected);
                    if (!selected.getFileName().toString().matches(SESSION)) continue;
                    // 跳过不受信任的子目录；明确指定的会话遇到链接则报告失败。
                    if (!ordinary(selected, true)) continue;
                    collect(selected, recent, limit, order, inspected);
                }
            }
        }
        return recent.stream().sorted(order.reversed()).map(Entry::path).toList();
    }

    private static void collect(Path directory, PriorityQueue<Entry> recent, int limit,
                                Comparator<Entry> order, int[] inspected) throws IOException {
        verify(directory);
        try (var entries = Files.newDirectoryStream(directory, "*.md")) {
            for (Path entry : entries) {
                count(inspected);
                if (!ordinary(entry, false)) continue;
                var candidate = new Entry(entry, Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis());
                if (recent.size() < limit) recent.add(candidate);
                else if (order.compare(candidate, recent.peek()) > 0) { recent.remove(); recent.add(candidate); }
            }
        }
    }

    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    private static boolean ordinary(Path path, boolean directory) {
        try {
            return !Files.isSymbolicLink(path) && (directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) && path.toRealPath().equals(path);
        } catch (IOException | SecurityException ignored) { return false; }
    }
    private static void verify(Path path) throws IOException {
        if (!ordinary(path, true)) throw new IOException("执行记录目录不存在、不可访问或被链接重定向");
    }
    private static void count(int[] value) throws IOException {
        if (++value[0] > MAX_ENTRIES) throw new IOException("日志条目过多，请用 ylc logs 会话ID 缩小范围");
    }
    private record Entry(Path path, long modified) { }
}
