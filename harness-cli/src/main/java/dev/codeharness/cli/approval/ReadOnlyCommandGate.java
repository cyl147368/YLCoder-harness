package dev.codeharness.cli.approval;

import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import dev.codeharness.cli.tools.CommandApproval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.HexFormat;
import java.util.ArrayList;

/** 严格把目录、Git 分支及状态查询翻译为固定参数；从不执行用户提供的 shell 文本。 */
final class ReadOnlyCommandGate {
    record FileIdentity(Path path, Object key, long size, FileTime modified) {}
    record Plan(Path workspace, String command, List<String> arguments, FileIdentity executable, Object workspaceKey,
                Object repositoryKey, String configuration) {
        Plan { arguments = List.copyOf(arguments); }
    }
    record Check(Optional<Plan> plan, String reason) {
        static Check rejected(String reason) { return new Check(Optional.empty(), reason); }
    }
    private record GitPlan(List<String> arguments, String configuration) {}

    Optional<Plan> plan(CommandApproval.Request request) {
        return check(request).plan();
    }

    Check check(CommandApproval.Request request) {
        Check outside = Check.rejected("此命令不在低风险自动审核范围内");
        try {
            String command = request.command();
            // 整个命令必须精确匹配；不做拆词、变量展开或危险片段删除。
            if (command.length() > 128 || command.codePoints().anyMatch(value -> Character.isISOControl(value)))
                return outside;
            Path workspace = request.workspace().toAbsolutePath().normalize();
            if (!workspace.equals(workspace.toRealPath()) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS))
                return outside;
            boolean git = command.startsWith("git ");
            GitPlan gitPlan = git ? gitArguments(workspace, command) : null;
            List<String> arguments = git ? gitPlan == null ? null : gitPlan.arguments()
                    : isWindows() ? windowsArguments(command) : unixArguments(command);
            if (arguments == null) return outside;
            Path executable = Path.of(arguments.getFirst()).toRealPath();
            if (executable.startsWith(workspace) || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS))
                return outside;
            BasicFileAttributes binary = Files.readAttributes(executable, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes directory = Files.readAttributes(workspace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // 执行的绝对路径也必须固定，不能在审核后经 PATH 再搜索。
            var canonicalArguments = new java.util.ArrayList<>(arguments);
            canonicalArguments.set(0, executable.toString());
            return new Check(Optional.of(new Plan(workspace, command, canonicalArguments,
                    new FileIdentity(executable, identity(executable, binary), binary.size(), binary.lastModifiedTime()),
                    identity(workspace, directory), git ? identity(workspace.resolve(".git"), Files.readAttributes(
                            workspace.resolve(".git"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)) : null,
                    gitPlan == null ? "" : gitPlan.configuration())), "");
        } catch (GitStatusConfig.Ineligible blocked) {
            return Check.rejected(blocked.getMessage());
        } catch (IOException | RuntimeException | LinkageError ignored) {
            return outside;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Object identity(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes.fileKey() != null) return attributes.fileKey();
        if (!isWindows()) throw new IOException("文件系统不提供稳定身份，不能复用自动审核");
        // Windows NIO 的 fileKey 可为 null；使用卷号与 128 位文件 ID 区分同路径的替换目录。
        Pointer handle = WindowsSystem.INSTANCE.CreateFileW(new WString(path.toString()), 0x80, 7,
                null, 3, 0x02000000, null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) throw new IOException("无法读取文件身份");
        try (Memory information = new Memory(24)) {
            if (!WindowsSystem.INSTANCE.GetFileInformationByHandleEx(handle, 18, information, 24))
                throw new IOException("文件系统不支持文件 ID 查询");
            return HexFormat.of().formatHex(information.getByteArray(0, 24));
        } finally { WindowsSystem.INSTANCE.CloseHandle(handle); }
    }

    private static List<String> windowsArguments(String command) {
        String builtin = switch (command) {
            case "pwd", "cd" -> "cd";
            case "ls", "dir", "dir /b" -> "dir /b";
            case "ls -a", "dir /a", "dir /a /b", "dir /b /a" -> "dir /b /a";
            case "ls -l" -> "dir";
            case "ls -la", "ls -al" -> "dir /a";
            default -> null;
        };
        if (builtin == null) return null;
        char[] path = new char[32768];
        int length = WindowsSystem.INSTANCE.GetSystemDirectoryW(path, path.length);
        if (length <= 0 || length >= path.length) return null;
        Path executable = Path.of(new String(path, 0, length)).resolve("cmd.exe");
        return List.of(executable.toString(), "/d", "/s", "/c", builtin);
    }

    private static List<String> unixArguments(String command) {
        String executable;
        List<String> flags;
        switch (command) {
            case "pwd" -> { executable = "pwd"; flags = List.of("-P"); }
            case "ls", "dir" -> { executable = "ls"; flags = List.of("-1"); }
            case "ls -a", "dir /a", "dir /a /b", "dir /b /a" -> { executable = "ls"; flags = List.of("-1a"); }
            case "dir /b" -> { executable = "ls"; flags = List.of("-1"); }
            case "ls -l" -> { executable = "ls"; flags = List.of("-l"); }
            case "ls -la", "ls -al" -> { executable = "ls"; flags = List.of("-la"); }
            default -> { return null; }
        }
        // 不读取 PATH，工作区或用户目录中的同名程序不能替换系统工具。
        Path systemBinary = Path.of("/bin", executable);
        if (!Files.isExecutable(systemBinary)) systemBinary = Path.of("/usr/bin", executable);
        if (!Files.isExecutable(systemBinary)) return null;
        var arguments = new java.util.ArrayList<String>();
        arguments.add(systemBinary.toString());
        arguments.addAll(flags);
        return arguments;
    }

    private static GitPlan gitArguments(Path workspace, String command) throws IOException {
        List<String> operation = switch (command) {
            case "git branch --show-current" -> List.of("branch", "--show-current");
            case "git status" -> List.of("status");
            case "git status --short", "git status -s" -> List.of("status", "--short");
            case "git status --porcelain", "git status --porcelain=v1" -> List.of("status", "--porcelain=v1");
            case "git status --porcelain=v2" -> List.of("status", "--porcelain=v2");
            case "git status -sb", "git status --short --branch", "git status --branch --short" -> List.of("status", "--short", "--branch");
            default -> null;
        };
        if (operation == null) return null;
        Path gitDirectory = workspace.resolve(".git");
        if (!Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS) || !gitDirectory.toRealPath().equals(gitDirectory)
                || Files.exists(gitDirectory.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(gitDirectory.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)) return null;
        // 不跟随父仓库、外置工作树、链接对象库或仓库内的重定向路径。
        try (var paths = Files.walk(gitDirectory, 32)) {
            var iterator = paths.iterator();
            int visited = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++visited > 10_000 || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(gitDirectory)) return null;
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && gitDirectory.relativize(path).getNameCount() >= 32) return null;
            }
        }
        Path git = installedGit(workspace);
        if (git == null) return null;
        boolean status = operation.getFirst().equals("status");
        String configuration = status ? GitStatusConfig.fingerprint(gitDirectory) : "";
        var arguments = new ArrayList<String>();
        arguments.add(git.toString());
        arguments.addAll(List.of("--no-pager", "--no-optional-locks", "--git-dir=" + gitDirectory,
                "--work-tree=" + workspace, "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
                "-c", "log.showSignature=false", "-c", "color.ui=false"));
        arguments.addAll(operation);
        // 子模块有独立配置，不能让已审核的主仓库查询进入未经审核的子仓库。
        if (status) arguments.add("--ignore-submodules=all");
        return new GitPlan(arguments, configuration);
    }

    static Path installedGit(Path workspace) {
        String search = System.getenv("PATH");
        if (search == null) return null;
        for (String value : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            try {
                if (value.isBlank()) continue;
                // 仅信任调用者已有 PATH 中的外部绝对目录；不搜索当前目录及脚本包装器。
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                Path directory = Path.of(value);
                if (!directory.isAbsolute()) continue;
                Path executable = directory.resolve(isWindows() ? "git.exe" : "git");
                if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) continue;
                Path canonical = executable.toRealPath();
                if (!canonical.startsWith(workspace.toRealPath())) return canonical;
            } catch (IOException | RuntimeException ignored) { /* 无效或工作区内的 PATH 条目不扩大候选范围。 */ }
        }
        return null;
    }

    private interface WindowsSystem extends StdCallLibrary {
        WindowsSystem INSTANCE = Native.load("kernel32", WindowsSystem.class);
        int GetSystemDirectoryW(char[] buffer, int size);
        Pointer CreateFileW(WString filename, int access, int sharing, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer information, int size);
        boolean CloseHandle(Pointer handle);
    }
}
