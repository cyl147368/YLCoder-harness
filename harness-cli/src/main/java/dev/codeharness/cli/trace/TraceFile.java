package dev.codeharness.cli.trace;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只新建唯一日志文件，持有原始句柄并在每次写入前核对路径、文件身份与硬链接数。 */
final class TraceFile implements AutoCloseable {
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final Path file;
    private final Map<Path, Object> directories;
    private final Object fileIdentity;
    private final FileChannel channel;
    private long bytes;

    private TraceFile(Path file, Map<Path, Object> directories, Object fileIdentity, FileChannel channel) {
        this.file = file;
        this.directories = directories;
        this.fileIdentity = fileIdentity;
        this.channel = channel;
    }

    static TraceFile create(Path workspace, String sessionId) throws IOException {
        if (workspace == null || sessionId == null || !sessionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))
            throw new IOException("日志标识无效");
        Path root = workspace.toAbsolutePath().normalize();
        verifyDirectory(root);
        Path state = root.resolve(".harness");
        ensureDirectory(state, false);
        Path logs = state.resolve("logs");
        ensureDirectory(logs, true);
        Path session = logs.resolve(sessionId);
        ensureDirectory(session, true);
        ensureIgnoreFile(logs);
        Map<Path, Object> identities = new LinkedHashMap<>();
        for (Path directory : List.of(root, state, logs, session)) identities.put(directory, identity(directory, true));
        String name = NAME_TIME.format(Instant.now()) + "-" + UUID.randomUUID() + ".md";
        Path file = session.resolve(name);
        FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), attributes(session, false));
        boolean complete = false;
        try {
            restrict(file, false);
            Object key = identity(file, false);
            TraceFile result = new TraceFile(file, identities, key, channel);
            result.verify();
            complete = true;
            return result;
        } finally {
            if (!complete) channel.close();
        }
    }

    Path path() { return file; }
    long size() { return bytes; }

    void append(String text) throws IOException {
        verify();
        if (channel.size() != bytes) throw new IOException("日志文件已被外部修改");
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer content = ByteBuffer.wrap(utf8);
        while (content.hasRemaining()) channel.write(content);
        bytes += utf8.length;
        // 每个完整事件即刻刷入，不依赖正常退出才保存。
        channel.force(false);
    }

    private void verify() throws IOException {
        for (var entry : directories.entrySet()) {
            verifyDirectory(entry.getKey());
            if (!entry.getValue().equals(identity(entry.getKey(), true))) throw new IOException("日志目录已被替换");
        }
        if (!fileIdentity.equals(identity(file, false))) throw new IOException("日志文件已被替换");
    }

    private static void ensureDirectory(Path path, boolean privateExisting) throws IOException {
        verifyDirectory(path.getParent());
        boolean created = false;
        try {
            Files.createDirectory(path, attributes(path.getParent(), true));
            created = true;
        } catch (FileAlreadyExistsException ignored) { /* 使用前仍验证其实际类型与规范路径。 */ }
        verifyDirectory(path);
        if (created || privateExisting) restrict(path, true);
    }

    private static void verifyDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || !path.toRealPath().equals(path))
            throw new IOException("日志目录不能被重定向");
        identity(path, true);
    }

    private static void ensureIgnoreFile(Path directory) throws IOException {
        Path ignore = directory.resolve(".gitignore");
        try (FileChannel out = FileChannel.open(ignore, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), attributes(directory, false))) {
            restrict(ignore, false);
            identity(ignore, false);
            out.write(StandardCharsets.UTF_8.encode("# 本地模型与工具追踪不进入版本控制。\n*\n"));
        } catch (FileAlreadyExistsException existing) {
            Object expected = identity(ignore, false);
            if (Files.size(ignore) > 64 * 1024) throw new IOException("日志忽略规则过大");
            String value = Files.readString(ignore, StandardCharsets.UTF_8);
            if (!expected.equals(identity(ignore, false))) throw new IOException("日志忽略文件已被替换");
            String finalRule = value.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .reduce((previous, current) -> current).orElse("");
            if (finalRule.equals("*")) return;
            // 只给本组件的忽略文件追加规则，不覆盖用户已有内容。
            try (FileChannel out = FileChannel.open(ignore, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                if (!expected.equals(identity(ignore, false))) throw new IOException("日志忽略文件已被替换");
                out.write(StandardCharsets.UTF_8.encode("\n*\n"));
            }
        }
    }

    private static FileAttribute<?>[] attributes(Path directory, boolean isDirectory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"))};
        }
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            var owner = acl.getOwner();
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (isDirectory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            List<AclEntry> entries = List.of(builder.build());
            return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return entries; }
            }};
        }
        throw new IOException("文件系统不支持私有日志权限");
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("文件系统不支持私有日志权限");
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(builder.build()));
    }

    private static Object identity(Path path, boolean directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
                || !path.toRealPath().equals(path)) throw new IOException("日志路径不能被重定向");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                Pointer handle = WindowsSystem.INSTANCE.CreateFileW(new WString(path.toString()), 0x80, 7,
                        null, 3, 0x02200000, null);
                if (handle == null || Pointer.nativeValue(handle) == -1L) throw new IOException("无法核对日志文件身份");
                try (Memory information = new Memory(52)) {
                    if (!WindowsSystem.INSTANCE.GetFileInformationByHandle(handle, information))
                        throw new IOException("无法核对日志文件身份");
                    if ((information.getInt(0) & 0x400) != 0) throw new IOException("日志路径含重解析点");
                    if (!directory && information.getInt(40) != 1) throw new IOException("日志文件不能是硬链接");
                    return information.getInt(28) + ":" + information.getInt(44) + ":" + information.getInt(48);
                } finally { WindowsSystem.INSTANCE.CloseHandle(handle); }
            } catch (LinkageError unavailable) { throw new IOException("文件身份检查不可用"); }
        }
        if (!directory) {
            Object count = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(count instanceof Number number) || number.longValue() != 1) throw new IOException("日志文件不能是硬链接");
        }
        if (attributes.fileKey() == null) throw new IOException("无法核对日志文件身份");
        return attributes.fileKey();
    }

    @Override public void close() throws IOException { channel.close(); }

    private interface WindowsSystem extends StdCallLibrary {
        WindowsSystem INSTANCE = Native.load("kernel32", WindowsSystem.class);
        Pointer CreateFileW(WString filename, int access, int sharing, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandle(Pointer handle, Pointer information);
        boolean CloseHandle(Pointer handle);
    }
}
