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
import java.security.MessageDigest;

/** 将支持的查询和 Maven 构建翻译为绑定计划；从不执行用户提供的 shell 文本。 */
final class ReadOnlyCommandGate {
    record FileIdentity(Path path, Object key, long size, FileTime modified) {}
    record Plan(Path workspace, String command, List<String> arguments, FileIdentity executable, Object workspaceKey,
                Object repositoryKey, String configuration, MavenBuildExecution.Plan mavenBuild) {
        Plan { arguments = List.copyOf(arguments); }
        boolean fixedReadOnlyShell() { return configuration.startsWith("shell:"); }
        boolean aiShell() { return configuration.startsWith("ai-shell:"); }
    }
    record Check(Optional<Plan> plan, String reason) {
        static Check rejected(String reason) { return new Check(Optional.empty(), reason); }
    }
    private record GitPlan(List<String> arguments, String configuration) {}
    private final MavenBuildExecution.RuntimeResolver mavenRuntime;

    ReadOnlyCommandGate() { this(MavenBuildExecution::discover); }
    ReadOnlyCommandGate(MavenBuildExecution.RuntimeResolver mavenRuntime) {
        this.mavenRuntime = java.util.Objects.requireNonNull(mavenRuntime);
    }

    Optional<Plan> plan(CommandApproval.Request request) {
        return check(request).plan();
    }

    Check check(CommandApproval.Request request) {
        Check outside = Check.rejected("命令不属于已支持的受限查询，未调用审核 AI；需要人工确认");
        try {
            String command = request.command();
            // 查询和构建各有独立解析器；不做变量展开或危险片段删除。
            if (command.codePoints().anyMatch(Character::isISOControl))
                return Check.rejected("命令含控制字符，未调用审核 AI");
            if (command.length() > 4096)
                return Check.rejected("命令超过 4096 字符，未调用审核 AI");
            boolean maven = MavenBuildExecution.recognizes(command);
            ReviewableShell.Plan shellCandidate = ReviewableShell.parse(command);
            boolean classifierCandidate = shellCandidate == null && ReviewableShell.reviewCandidate(command);
            Path workspace = request.workspace().toAbsolutePath().normalize();
            if (!workspace.equals(workspace.toRealPath()) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS))
                return Check.rejected("工作区不是规范的真实目录，未调用审核 AI");
            if (maven) {
                if (MavenBuildQuery.parse(command) == null)
                    return Check.rejected("Maven 参数包含未支持的目标、外部配置或执行入口，未调用审核 AI；需要人工确认");
                MavenBuildExecution.Plan build = MavenBuildExecution.plan(request, mavenRuntime.resolve(workspace));
                BasicFileAttributes directory = Files.readAttributes(workspace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return new Check(Optional.of(new Plan(workspace, command, List.of(), null,
                        identity(workspace, directory), null, build.fingerprint(), build)), "");
            }
            // 复合链即使以 git 开头也不能进入单命令 Git 分支，否则后续状态/管道
            // 会被忽略，且永远无法生成受限 shell 计划。
            boolean git = shellCandidate == null && command.startsWith("git ");
            GitPlan gitPlan = git ? gitArguments(workspace, command) : null;
            if (git && gitPlan == null && ReadOnlyQuery.git(command) != null)
                return Check.rejected("Git 查询未通过仓库边界检查，或未找到工作区外的 Git 程序，未调用审核 AI");
            List<String> arguments = git ? gitPlan == null ? null : gitPlan.arguments()
                    : timeArguments(command);
            if (!git && arguments == null) {
                List<String> flags = ReadOnlyQuery.ripgrep(command);
                Path rg = flags == null ? null : installedTool(workspace, "rg");
                if (rg != null) {
                    var query = new ArrayList<String>();
                    query.add(rg.toString());
                    query.addAll(flags);
                    arguments = query;
                }
            }
            ShellPlan shell = null;
            if (!git && arguments == null) {
                if (shellCandidate != null) {
                    shell = shellArguments(workspace, shellCandidate);
                    if (shell != null) arguments = shell.arguments();
                }
            }
            if (arguments == null && classifierCandidate) {
                shell = shellArguments(workspace, ReviewableShell.syntax(command), true);
                arguments = shell.arguments();
            }
            if (!git && arguments == null) arguments = isWindows() ? windowsArguments(command) : unixArguments(command);
            if (arguments == null) return outside;
            Path executable = Path.of(arguments.getFirst()).toRealPath();
            if (executable.startsWith(workspace) || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS))
                return Check.rejected("程序必须是工作区外可验证的普通文件，未调用审核 AI；需要人工确认");
            BasicFileAttributes binary = Files.readAttributes(executable, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes directory = Files.readAttributes(workspace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // 执行的绝对路径也必须固定，不能在审核后经 PATH 再搜索。
            var canonicalArguments = new java.util.ArrayList<>(arguments);
            canonicalArguments.set(0, executable.toString());
            return new Check(Optional.of(new Plan(workspace, command, canonicalArguments,
                    new FileIdentity(executable, identity(executable, binary), binary.size(), binary.lastModifiedTime()),
                    identity(workspace, directory), gitPlan != null ? identity(workspace.resolve(".git"), Files.readAttributes(
                            workspace.resolve(".git"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)) : null,
                    shell != null ? shell.configuration() : gitPlan == null ? "" : gitPlan.configuration(), null)), "");
        } catch (GitStatusConfig.Ineligible | MavenBuildExecution.Ineligible blocked) {
            return Check.rejected(blocked.getMessage());
        } catch (IOException | RuntimeException | LinkageError failure) {
            return Check.rejected("无法可靠验证程序或查询目标的身份，未调用审核 AI；"
                    + "请检查工作区权限和文件系统支持，需要人工确认");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 时间查询只允许固定格式，并改为直接启动可信系统程序，不执行原始 shell 文本。 */
    private static List<String> timeArguments(String command) {
        TimeQuery.Query query = TimeQuery.parse(command);
        if (query == null) return null;
        if (isWindows()) {
            Path system = windowsSystemDirectory();
            if (system == null) return null;
            Path executable = system.resolve("WindowsPowerShell/v1.0/powershell.exe");
            return List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); " + query.windowsExpression());
        }
        if (query.powerShell()) return null;
        Path executable = Files.isExecutable(Path.of("/bin/date")) ? Path.of("/bin/date") : Path.of("/usr/bin/date");
        if (!Files.isExecutable(executable)) return null;
        return query.dateFormat().isEmpty() ? List.of(executable.toString()) : List.of(executable.toString(), query.dateFormat());
    }

    static Object identity(Path path, BasicFileAttributes attributes) throws IOException {
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
        Path system = windowsSystemDirectory();
        if (system == null) return null;
        Path executable = system.resolve("cmd.exe");
        return List.of(executable.toString(), "/d", "/s", "/c", builtin);
    }

    private static Path windowsSystemDirectory() {
        char[] path = new char[32768];
        int length = WindowsSystem.INSTANCE.GetSystemDirectoryW(path, path.length);
        return length <= 0 || length >= path.length ? null : Path.of(new String(path, 0, length));
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
        List<String> operation = ReadOnlyQuery.git(command);
        if (operation == null) return null;
        return gitArguments(workspace, operation);
    }

    private static GitPlan gitArguments(Path workspace, List<String> operation) throws IOException {
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
        boolean diff = operation.getFirst().equals("diff");
        // 精确的分支名查询不读取工作树，不会调用 clean/process 过滤器；仍绑定配置指纹并检查外部配置。
        boolean currentBranchOnly = operation.equals(List.of("branch", "--show-current"));
        String configuration = GitStatusConfig.fingerprint(gitDirectory, !currentBranchOnly);
        if (diff) GitDiffQuery.validatePaths(workspace, operation);
        if (operation.getFirst().equals("show")) {
            var safeShow = new ArrayList<String>(operation);
            safeShow.addAll(1, List.of("--no-ext-diff", "--no-textconv", "--ignore-submodules=all"));
            operation = safeShow;
        }
        var arguments = new ArrayList<String>();
        arguments.add(git.toString());
        arguments.addAll(List.of("--no-pager", "--no-optional-locks", "--git-dir=" + gitDirectory,
                "--work-tree=" + workspace, "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
                "-c", "log.showSignature=false", "-c", "color.ui=false"));
        if (diff) arguments.add("--literal-pathspecs");
        arguments.addAll(operation);
        // 子模块有独立配置，不能让已审核的主仓库查询进入未经审核的子仓库。
        if (status) arguments.add("--ignore-submodules=all");
        return new GitPlan(arguments, configuration);
    }

    static Path installedGit(Path workspace) {
        return installedTool(workspace, "git");
    }

    record ShellPlan(List<String> arguments, String configuration) {}



    /**
     * 把已解析的只读链绑定成固定程序和参数。Git 使用同一套仓库配置指纹与隔离选项；
     * 筛选程序只从系统或 Git 安装目录取绝对路径；文件操作数在工作区内逐级校验。
     */
    ShellPlan shellArguments(Path workspace, ReviewableShell.Plan candidate) throws IOException {
        return shellArguments(workspace, candidate, false);
    }

    private ShellPlan shellArguments(Path workspace, ReviewableShell.Plan candidate, boolean classify) throws IOException {
        if (candidate == null) throw new GitStatusConfig.Ineligible("命令结构无法可靠解析，需要人工确认");
        var canonical = new ArrayList<String>();
        var configuration = new StringBuilder();
        for (int index = 0; index < candidate.segments().size(); index++) {
            ReviewableShell.Segment segment = candidate.segments().get(index);
            List<String> words = segment.words();
            List<String> planned;
            if (words.getFirst().equals("git")) {
                List<String> operation = ReadOnlyQuery.git(ReviewableShell.join(words));
                if (operation == null && classify) operation = words.subList(1, words.size());
                GitPlan git = operation == null ? null : gitArguments(workspace, operation);
                if (git == null) throw new GitStatusConfig.Ineligible(
                        "只读命令链中的 Git 阶段未通过仓库边界检查，需要人工确认");
                planned = git.arguments();
                configuration.append(git.configuration());
                if (classify) bindClassifierPaths(workspace, words, configuration);
                appendExecutableIdentity(configuration, Path.of(planned.getFirst()));
            } else if (words.getFirst().equals("echo")) {
                planned = words;
            } else if (words.getFirst().equals("ls")) {
                planned = listingArguments(workspace, words, configuration);
                if (planned != null && Path.of(planned.getFirst()).isAbsolute())
                    appendExecutableIdentity(configuration, Path.of(planned.getFirst()));
            } else if (words.getFirst().equals("pwd")) {
                planned = workingDirectoryArguments();
            } else if (words.getFirst().equals("rg")) {
                List<String> files = GitFilesQuery.parse(words);
                if (files == null) throw new GitStatusConfig.Ineligible(
                        "组合命令中的 rg --files 参数未通过检查，需要人工确认");
                GitPlan git = gitArguments(workspace, files);
                if (git == null) throw new GitStatusConfig.Ineligible(
                        "组合命令中的文件清单未通过 Git 边界检查，需要人工确认");
                planned = git.arguments();
                configuration.append(git.configuration());
                appendExecutableIdentity(configuration, Path.of(planned.getFirst()));
            } else {
                Path tool = companionTool(ReadOnlyCommandGate.installedGit(workspace), words.getFirst());
                // Windows 系统 find 是文本筛选器，不能替代模型请求的类 Unix 目录查询。
                if (tool == null && classify && !(isWindows() && words.getFirst().equals("find")))
                    tool = installedTool(workspace, words.getFirst());
                if (tool == null) throw new GitStatusConfig.Ineligible(
                        "只读命令链需要的固定 head/tail/wc 程序不可用，需要人工确认");
                planned = new ArrayList<>();
                planned.add(tool.toString());
                if (words.getFirst().equals("wc") && words.size() > 2) {
                    planned.add(words.get(1));
                    planned.add("--");
                    for (String operand : words.subList(2, words.size())) {
                        ShellPathValidation.bind(workspace, operand, true, configuration);
                        planned.add(operand);
                    }
                } else {
                    planned.addAll(words.subList(1, words.size()));
                    if (classify) bindClassifierPaths(workspace, words, configuration);
                }
                if (tool.startsWith(workspace)) throw new GitStatusConfig.Ineligible("不自动执行工作区内的程序");
                appendExecutableIdentity(configuration, tool);
            }
            canonical.add(joinForShell(planned, segment.discardStandardError(), segment.mergeStandardError()));
            if (index < candidate.operators().size()) {
                String operator = candidate.operators().get(index);
                canonical.add(isWindows() && operator.equals(";") ? "&" : operator);
            }
        }
        String command = String.join(" ", canonical);
        List<String> arguments;
        if (isWindows()) {
            Path system = windowsSystemDirectory();
            if (system == null) throw new GitStatusConfig.Ineligible(
                    "无法定位只读命令链的系统 shell，需要人工确认");
            arguments = List.of(system.resolve("cmd.exe").toString(), "/d", "/s", "/c", command);
        } else {
            Path shell = Files.isExecutable(Path.of("/bin/sh")) ? Path.of("/bin/sh") : Path.of("/usr/bin/sh");
            if (!Files.isExecutable(shell)) throw new GitStatusConfig.Ineligible(
                    "无法定位只读命令链的系统 shell，需要人工确认");
            arguments = List.of(shell.toString(), "-c", command);
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((configuration + "\n" + command).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new ShellPlan(arguments, (classify ? "ai-shell:" : "shell:") + hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void appendExecutableIdentity(StringBuilder configuration, Path executable) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                executable, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        configuration.append('\n').append(executable).append('|').append(identity(executable, attributes))
                .append('|').append(attributes.size()).append('|').append(attributes.lastModifiedTime());
    }

    private static Path companionTool(Path git, String name) {
        var candidates = new ArrayList<Path>();
        if (isWindows() && git != null && git.getParent() != null && git.getParent().getParent() != null) {
            candidates.add(git.getParent().getParent().resolve("usr/bin").resolve(name + ".exe"));
        }
        if (!isWindows()) {
            candidates.add(Path.of("/usr/bin", name));
            candidates.add(Path.of("/bin", name));
        }
        for (Path candidate : candidates) {
            try {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(candidate))
                    return candidate.toRealPath();
            } catch (IOException | RuntimeException ignored) { /* 继续检查固定系统位置。 */ }
        }
        return null;
    }

    private static List<String> listingArguments(Path workspace, List<String> words,
                                                  StringBuilder configuration) throws IOException {
        Path ls = companionTool(installedGit(workspace), "ls");
        if (ls == null || ls.startsWith(workspace)) throw new GitStatusConfig.Ineligible(
                "未找到工作区外的 ls 程序，需要人工确认");
        var result = new ArrayList<String>();
        var operands = new ArrayList<String>();
        result.add(ls.toString());
        for (String word : words.subList(1, words.size())) {
            if (word.startsWith("-")) result.add(word);
            else if (word.equals("*/")) {
                // 显式展开目录操作数，不能把 glob 加引号后交给 shell 或改写成忽略参数的 dir。
                try (var entries = Files.list(workspace)) {
                    var directories = entries.filter(entry -> !entry.getFileName().toString().startsWith("."))
                            .filter(Files::isDirectory).sorted().limit(129).toList();
                    if (directories.size() > 128) throw new GitStatusConfig.Ineligible("目录展开超过 128 项，需要人工确认");
                    for (Path entry : directories) operands.add("./" + entry.getFileName() + "/");
                    if (directories.isEmpty()) throw new GitStatusConfig.Ineligible("目录通配符没有匹配项，需要人工确认");
                }
            } else operands.add(word);
        }
        if (operands.isEmpty()) operands.add(".");
        result.add("--");
        for (String operand : operands) {
            ShellPathValidation.bind(workspace, operand, false, configuration);
            result.add(operand);
        }
        return result;
    }

    private static void bindClassifierPaths(Path workspace, List<String> words,
                                            StringBuilder configuration) throws IOException {
        for (String word : words.subList(1, words.size())) {
            String value = word;
            if (word.startsWith("-")) {
                int equals = word.indexOf('=');
                if (equals < 0) continue;
                value = word.substring(equals + 1);
            }
            // 参数的业务含义仍由分类器判断；明显的文件操作数不能绕开确定性的路径校验。
            if (value.contains("/") || Files.exists(workspace.resolve(value), LinkOption.NOFOLLOW_LINKS))
                ShellPathValidation.bind(workspace, value, !words.getFirst().equals("find"), configuration);
        }
    }

    private static List<String> workingDirectoryArguments() {
        if (isWindows()) return List.of("cd");
        Path executable = Files.isExecutable(Path.of("/bin/pwd")) ? Path.of("/bin/pwd") : Path.of("/usr/bin/pwd");
        return List.of(executable.toString(), "-P");
    }

    private static String joinForShell(List<String> arguments, boolean discardStandardError,
                                       boolean mergeStandardError) {
        String command = isWindows() ? arguments.stream().map(ReadOnlyCommandGate::windowsQuote)
                .collect(java.util.stream.Collectors.joining(" "))
                : arguments.stream().map(ReadOnlyCommandGate::unixQuote)
                .collect(java.util.stream.Collectors.joining(" "));
        if (discardStandardError) return command + (isWindows() ? " 2>NUL" : " 2>/dev/null");
        if (mergeStandardError) return command + " 2>&1";
        return command;
    }

    private static String windowsQuote(String value) {
        if (value.codePoints().anyMatch(code -> Character.isISOControl(code) || "\"%!^&|<>()".indexOf(code) >= 0))
            throw new IllegalArgumentException("命令参数含 cmd 展开字符，无法安全序列化");
        if (value.matches("[A-Za-z0-9_./:=+,-]+")) return value;
        // 白名单不会让带引号的值到达这里；固定程序与工作区路径不含可转义的双引号。
        return '"' + value + '"';
    }

    private static String unixQuote(String value) {
        if (value.matches("[A-Za-z0-9_./:=+,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Path installedTool(Path workspace, String name) {
        String search = System.getenv("PATH");
        if (search == null) return null;
        for (String value : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            try {
                if (value.isBlank()) continue;
                // 仅信任调用者已有 PATH 中的外部绝对目录；不搜索当前目录及脚本包装器。
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                Path directory = Path.of(value);
                if (!directory.isAbsolute()) continue;
                Path executable = directory.resolve(isWindows() ? name + ".exe" : name);
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
