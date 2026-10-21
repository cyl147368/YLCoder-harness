package dev.codeharness.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.agent.ToolHistory;
import dev.codeharness.core.model.ChatMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区本地检查点，使用协作式进程锁和原子替换。
 * 进程中断后留下的要么是原检查点，要么是完整的新检查点；
 * 忽略遗留的 checkpoint-*.tmp 文件。文件和目录未执行 fsync，因此
 * 不保证断电后的持久性。不支持原子移动时直接失败，不使用降级方案。
 * 规范路径检查拒绝链接和 junction，但无法以沙箱方式防御并发的
 * 恶意文件系统修改。会话使用期间不得删除锁文件。
 */
public final class SessionStore {
    private static final int MAX_BYTES = 32_000_000;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_BYTES).maxNestingDepth(100).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path root;
    private final Path state;
    private final Path directory;
    private final String workspace;
    private final Map<String, OwnedLease> leases = new ConcurrentHashMap<>();

    public SessionStore(Path workspace) throws IOException {
        root = workspace.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("工作区必须是目录");
        state = root.resolve(".harness");
        ensurePrivateDirectory(state);
        directory = state.resolve("sessions");
        verifyDirectory(state);
        ensurePrivateDirectory(directory);
        ensureIgnoreFile();
        this.workspace = root.toString();
    }

    /** 会话和锁不应成为 git clean -fd 的目标；不改动项目根目录的忽略规则。 */
    private void ensureIgnoreFile() throws IOException {
        Path ignore = directory.resolve(".gitignore");
        try (var channel = FileChannel.open(ignore, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), privateAttributes(directory, false));
             var output = java.nio.channels.Channels.newOutputStream(channel)) {
            output.write("# YLCoder 本地会话及锁文件，不纳入 Git 清理或版本控制。\n*\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (FileAlreadyExistsException existing) {
            // 尊重已有普通忽略文件，禁止跟随重定向写入外部文件。
            if (!Files.isRegularFile(ignore, LinkOption.NOFOLLOW_LINKS) || !ignore.toRealPath().equals(ignore))
                throw new IOException("会话忽略文件必须是普通文件");
        }
    }

    public record Session(int version, String id, String workspace, String provider, String model,
                          String status, String updatedAt, List<ChatMessage> transcript) {
        public Session { transcript = List.copyOf(transcript); }
    }

    public String newId() { return UUID.randomUUID().toString(); }

    public record Summary(String id, String provider, String model, String status, String updatedAt, String task) {}
    private record Candidate(String id, FileTime modified) {}

    /** 最多读取最近的指定数量检查点；只返回列表需要的摘要，不保留整段对话。 */
    public List<Summary> recent(int limit) throws IOException {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("会话列表数量必须在 1 到 50 之间");
        verifyDirectory(root);
        verifyDirectory(state);
        verifyDirectory(directory);
        List<Candidate> candidates;
        try (var files = Files.list(directory)) {
            candidates = files.map(file -> {
                String name = file.getFileName().toString();
                if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}\\.json")) return null;
                try {
                    var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    return attributes.isRegularFile() ? new Candidate(name.substring(0, name.length() - 5), attributes.lastModifiedTime()) : null;
                } catch (IOException failure) { return null; }
            }).filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(Candidate::modified).reversed().thenComparing(Candidate::id))
                    .limit(limit).toList();
        }
        var summaries = new ArrayList<Summary>();
        for (Candidate candidate : candidates) {
            try {
                Session session = load(candidate.id());
                String task = session.transcript().stream().filter(message -> message.role() == ChatMessage.Role.USER)
                        .map(ChatMessage::content).findFirst().orElse("（没有用户任务）");
                int end = task.offsetByCodePoints(0, Math.min(80, task.codePointCount(0, task.length())));
                summaries.add(new Summary(session.id(), session.provider(), session.model(), session.status(),
                        session.updatedAt(), task.substring(0, end) + (end < task.length() ? "…" : "")));
            } catch (IOException failure) { /* 损坏、已移除或重定向的检查点不能参与恢复。 */ }
        }
        return List.copyOf(summaries);
    }

    public Session load(String id) throws IOException {
        Path file = path(id, ".json");
        Session value;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = java.nio.channels.Channels.newInputStream(channel)) {
            if (channel.size() > MAX_BYTES) throw new IOException("会话超过 32 MB 上限");
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("会话超过 32 MB 上限");
            try { value = JSON.readValue(bytes, Session.class); }
            catch (IOException | IllegalArgumentException failure) {
                throw new IOException("会话检查点格式错误", failure);
            }
        }
        validate(value, id);
        return value;
    }

    public void save(String id, String provider, String model, String status, List<ChatMessage> transcript) throws IOException {
        Session value;
        try { value = new Session(1, id, workspace, provider, model, status, Instant.now().toString(), transcript); }
        catch (RuntimeException failure) { throw new IOException("会话记录无效", failure); }
        validate(value, id);
        OwnedLease owned = leases.get(id);
        if (owned != null && owned.lease.lock().isValid()) {
            if (owned.owner != Thread.currentThread()) throw new IOException("会话已被另一线程使用");
            saveLocked(id, value);
        } else {
            try (Lease ignored = lock(id)) { saveLocked(id, value); }
        }
    }

    private void saveLocked(String id, Session value) throws IOException {
        Path target = path(id, ".json");
        Path temporary = Files.createTempFile(directory, "checkpoint-", ".tmp", privateAttributes(directory, false));
        try {
            try (OutputStream output = new LimitedOutput(Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))) {
                JSON.writeValue(output, value);
            }
            path(id, ".json");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public Lease lock(String id) throws IOException {
        leases.entrySet().removeIf(entry -> !entry.getValue().lease.lock().isValid());
        Path file = path(id, ".lock");
        FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), privateAttributes(directory, false));
        try {
            restrictPermissions(file, false);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("会话已被使用");
            Lease lease = new Lease(channel, lock);
            leases.put(id, new OwnedLease(lease, Thread.currentThread()));
            return lease;
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("会话已被使用或无法加锁", failure);
        }
    }

    private Path path(String id, String extension) throws IOException {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")) throw new IOException("会话 ID 无效");
        verifyDirectory(root);
        verifyDirectory(state);
        verifyDirectory(directory);
        Path result = directory.resolve(id + extension);
        if (Files.isSymbolicLink(result)) throw new IOException("会话文件不能是符号链接");
        if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || !result.toRealPath().equals(result))) {
            throw new IOException("会话文件必须是会话目录中的普通文件");
        }
        return result;
    }

    private void validate(Session value, String id) throws IOException {
        if (value == null || value.version() != 1 || id == null || !id.equals(value.id()) || !workspace.equals(value.workspace())) {
            throw new IOException("会话版本、ID 或工作区不匹配");
        }
        if (value.provider() == null || value.provider().isBlank() || value.model() == null || value.model().isBlank()
                || value.status() == null || value.status().isBlank() || value.updatedAt() == null) {
            throw new IOException("会话必须包含 provider、model、status 和时间戳");
        }
        try { Instant.parse(value.updatedAt()); }
        catch (RuntimeException failure) { throw new IOException("会话时间戳无效", failure); }
        // 待完成的结果可以构成有效检查点。相互矛盾的证据绝不能被
        // 静默修复，也不能通过原子替换覆盖原先有效的检查点。
        try { ToolHistory.validate(value.transcript(), true); }
        catch (RuntimeException failure) { throw new IOException("会话工具历史无效"); }
    }

    private static void verifyDirectory(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("会话目录已被删除或不存在");
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path))
            throw new IOException("会话目录不能通过链接或 junction 重定向");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("会话路径不是目录");
    }

    private static void ensurePrivateDirectory(Path path) throws IOException {
        verifyDirectory(path.getParent());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path, privateAttributes(path.getParent(), true)); }
            catch (FileAlreadyExistsException ignored) { /* 使用前验证并发创建的目录。 */ }
        }
        verifyDirectory(path);
        restrictPermissions(path, true);
    }

    private static FileAttribute<?>[] privateAttributes(Path directory, boolean isDirectory) throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"))}
                : new FileAttribute<?>[0];
    }

    private static void restrictPermissions(Path file, boolean isDirectory) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"));
    }

    private record OwnedLease(Lease lease, Thread owner) {}

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream delegate;
        private long count;
        LimitedOutput(OutputStream delegate) { this.delegate = delegate; }
        private void reserve(int size) throws IOException {
            if (count + size > MAX_BYTES) throw new IOException("会话超过 32 MB 上限");
            count += size;
        }
        @Override public void write(int value) throws IOException { reserve(1); delegate.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); delegate.write(value, offset, length); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    public record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
        }
    }
}
