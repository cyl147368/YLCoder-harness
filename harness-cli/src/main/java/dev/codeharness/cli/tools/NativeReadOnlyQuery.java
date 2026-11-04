package dev.codeharness.cli.tools;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** 将受支持的只读查询转换成有界文件访问；从不启动 shell 或外部程序。 */
public final class NativeReadOnlyQuery {
    private static final Set<String> EXCLUDED = Set.of(".git", ".harness");
    private static final int MAX_COMMAND_CHARS = 4096;
    private static final int MAX_GLOBS = 64;
    private static final Set<String> SAFE_FILE_GLOBS = Set.of(
            "AGENTS.md", "README.md", "*.java", "pom.xml", "*test*", "*Test*", ".github/**",
            "application*", "*Security*", "*Controller.java", "*ServiceImpl.java",
            "*Application.java", "*ExceptionHandler*", "application*.yml", "application*.yaml");
    private static final Limits DEFAULT_LIMITS = new Limits(10_000, 1_000, 11_000, 32, Duration.ofSeconds(2));
    private static final String RG_NOTICE = "[原生文件名查询：不读取 .gitignore/.ignore；排除 .git/.harness，跳过链接和 junction。]\n";
    private static final String WHERE_NOTICE = "[原生 where 文件名查询：返回绝对路径；排除 .git/.harness，跳过链接和 junction；不展开 PATH/PATHEXT。]\n";
    private static final String SEARCH_NOTICE = "[原生正文查询：不读取 .gitignore/.ignore；排除内部目录、敏感文件、链接和 junction；跳过二进制、无效 UTF-8 和过大文件。]\n";
    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final long MAX_SEARCH_BYTES = 8_000_000;

    private NativeReadOnlyQuery() {}

    /** 审批只绑定查询和工作区的稳定身份，不接受外部可执行文件或任意遍历选项。 */
    public record Plan(Path workspace, String command, String workspaceIdentity) {
        public Plan {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(workspaceIdentity, "workspaceIdentity");
            if (!workspace.isAbsolute() || !workspace.equals(workspace.normalize()) || !supports(command)
                    || workspaceIdentity.isBlank()) throw new IllegalArgumentException("无效的原生查询计划");
        }
    }

    record Limits(int visits, int results, int outputChars, int depth, Duration timeout) {
        Limits {
            if (visits <= 0 || results <= 0 || outputChars < 200 || depth < 1
                    || timeout == null || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("原生查询限制必须为正数");
        }
    }

    private record Query(boolean recursive, boolean hidden, boolean longListing, boolean agentsOnly,
                         boolean workingDirectory, boolean ripgrep, List<String> fileGlobs, boolean excludeTarget,
                          boolean windowsWhere, NativeSearchQuery.Spec search) {
        Query(boolean recursive, boolean hidden, boolean longListing, boolean agentsOnly,
              boolean workingDirectory, boolean ripgrep) {
            this(recursive, hidden, longListing, agentsOnly, workingDirectory, ripgrep, List.of(), false, false, null);
        }
        Query { fileGlobs = List.copyOf(fileGlobs); }
    }
    private record Entry(Path path, BasicFileAttributes attributes) {}

    public static boolean supports(String command) { return parse(command) != null; }

    public static Plan plan(CommandApproval.Request request) throws IOException {
        if (!supports(request.command())) throw new IOException("命令不属于原生只读查询范围");
        Path workspace = request.workspace().toAbsolutePath().normalize();
        BasicFileAttributes attributes = checkedDirectory(workspace);
        return new Plan(workspace, request.command(), identity(workspace, attributes));
    }

    public static String execute(Plan plan) throws IOException, InterruptedException {
        return execute(plan, DEFAULT_LIMITS);
    }

    static String execute(Plan plan, Limits limits) throws IOException, InterruptedException {
        Objects.requireNonNull(plan, "plan");
        checkInterrupted();
        verifyDirectory(plan.workspace(), plan.workspaceIdentity());
        Query query = parse(plan.command());
        State state = new State(limits, query.search(), plan.workspace());
        if (query.workingDirectory()) state.append(display(plan.workspace().toString()) + "\n");
        else {
            if (query.ripgrep()) state.output.append(RG_NOTICE);
            if (query.windowsWhere()) state.output.append(WHERE_NOTICE);
            if (query.search() != null && !query.search().files()) state.output.append(SEARCH_NOTICE);
            if (query.search() != null) {
                for (String root : query.search().paths()) {
                    Path selected = plan.workspace().resolve(root).normalize();
                    if (!Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) state.missingRoots.add(root);
                }
            }
            walk(plan.workspace(), plan.workspace(), query, state, 0, plan.workspaceIdentity());
            // 原生查询统一视为成功完成，不模拟 where.exe 的进程退出码 1；截断时不声称没有匹配。
            if ((query.windowsWhere() || query.search() != null) && state.results == 0 && !state.truncated)
                state.append("[未找到匹配文件]\n");
        }
        checkInterrupted();
        // 同路径目录在审批后被替换时，丢弃全部结果，不把新目录当作已批准的工作区。
        verifyDirectory(plan.workspace(), plan.workspaceIdentity());
        return state.result();
    }

    private static Query parse(String command) {
        if (command == null || command.length() > MAX_COMMAND_CHARS
                || command.codePoints().anyMatch(Character::isISOControl)) return null;
        switch (command) {
            case "pwd", "cd": return new Query(false, false, false, false, true, false);
            case "ls", "dir", "dir /b": return new Query(false, false, false, false, false, false);
            case "ls -a", "dir /a", "dir /a /b", "dir /b /a":
                return new Query(false, true, false, false, false, false);
            case "ls -l": return new Query(false, false, true, false, false, false);
            case "ls -la", "ls -al": return new Query(false, true, true, false, false, false);
            case "find . -name AGENTS.md -not -path './.git/*'",
                 "find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'":
                return new Query(true, true, false, true, false, false);
            default: break;
        }
        String[] words = command.split(" +", -1);
        if (words.length > 0 && (words[0].equalsIgnoreCase("where") || words[0].equalsIgnoreCase("where.exe")))
            return parseWhere(words);
        NativeSearchQuery.Spec search = NativeSearchQuery.parse(command);
        return search == null ? null : new Query(true, search.hidden(), false, false, false, search.files(),
                search.globs(), false, false, search);
    }

    /** 仅兼容工作区内的递归文件名查找，不接受 where 的环境变量、盘符、可执行文件搜索或额外开关。 */
    private static Query parseWhere(String[] words) {
        if (words.length < 4 || words.length - 3 > MAX_GLOBS || !words[1].equalsIgnoreCase("/r")) return null;
        String directory = unquote(words[2]);
        if (!Set.of(".", "./", ".\\").contains(directory)) return null;
        List<String> globs = new ArrayList<>();
        for (int index = 3; index < words.length; index++) {
            String glob = unquote(words[index]);
            // 复用相同的已审核模式集合，但 where 只接收文件名，拒绝路径 glob 和否定条件。
            if (glob.indexOf('/') >= 0 || glob.indexOf('\\') >= 0
                    || SAFE_FILE_GLOBS.stream().noneMatch(allowed -> allowed.equalsIgnoreCase(glob))) return null;
            globs.add(glob);
        }
        return new Query(true, true, false, false, false, false, globs, false, true, null);
    }

    private static String unquote(String word) {
        if (word.length() >= 2 && (word.charAt(0) == '\'' || word.charAt(0) == '"')
                && word.charAt(word.length() - 1) == word.charAt(0))
            return word.substring(1, word.length() - 1);
        return word;
    }

    private static void walk(Path workspace, Path directory, Query query, State state, int depth,
                             String expectedIdentity) throws IOException, InterruptedException {
        if (!state.continueWork()) return;
        verifyDirectory(directory, expectedIdentity);
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            verifyDirectory(directory, expectedIdentity);
            for (Path entry : stream) {
                if (!state.continueWork()) break;
                if (state.visited >= state.limits.visits()) { state.truncated = true; break; }
                state.visited++;
                if (query.recursive() && EXCLUDED.contains(entry.getFileName().toString().toLowerCase(Locale.ROOT))) continue;
                // !target 同时排除各层同名文件和目录，提前剪枝，避免扫描构建产物。
                if (query.excludeTarget() && entry.getFileName().toString().equals("target")) continue;
                if (!state.inScope(entry)) continue;
                if (query.search() != null && !query.search().files() && sensitive(workspace, entry)) continue;
                try {
                    BasicFileAttributes attributes = safeAttributes(workspace, entry);
                    if (attributes != null && state.excluded.test(relative(workspace, entry), attributes.isDirectory())) continue;
                    if (attributes == null || (!query.hidden() && hidden(entry)
                            && !explicitlyIncludedGithub(workspace, entry, query, attributes))) continue;
                    entries.add(new Entry(entry, attributes));
                } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ignored) {
                    // 删除中或不可访问的项目不扩大可见范围；目录身份仍在退出前复核。
                }
            }
        }
        verifyDirectory(directory, expectedIdentity);
        entries.sort(Comparator.comparing(entry -> entry.path().getFileName().toString()));
        for (Entry entry : entries) {
            if (!state.continueWork()) break;
            Path path = entry.path();
            // 不复用枚举时的路径判断，避免同一路径被替换为链接后继续访问。
            BasicFileAttributes attributes;
            try { attributes = safeAttributes(workspace, path); }
            catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ignored) { continue; }
            if (attributes == null) continue;
            if (!query.recursive()) {
                String name = display(path.getFileName().toString()) + (attributes.isDirectory() ? "/" : "");
                state.append((query.longListing() ? (attributes.isDirectory() ? "目录\t—\t" : "文件\t" + attributes.size() + " 字节\t") : "") + name + "\n");
            } else if (attributes.isDirectory()) {
                if (depth + 1 >= state.limits.depth() || state.visited >= state.limits.visits()) {
                    state.truncated = true;
                    continue;
                }
                walk(workspace, path, query, state, depth + 1, identity(path, attributes));
            } else if (attributes.isRegularFile() && state.selectedFile(path) && (query.search() == null
                    ? matchesFileGlobs(workspace, path, query) : state.matchesPath.test(relative(workspace, path), false))) {
                if (query.search() != null && !query.search().files()) {
                    try { searchFile(workspace, path, attributes, query.search(), state); }
                    catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ignored) { state.skipped++; }
                }
                else state.append(display(query.windowsWhere() ? path.toString() : relative(workspace, path)) + "\n");
            }
        }
        verifyDirectory(directory, expectedIdentity);
    }

    private static BasicFileAttributes safeAttributes(Path workspace, Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || (!attributes.isDirectory() && !attributes.isRegularFile())) return null;
        Path real = path.toRealPath();
        return real.equals(path) && real.startsWith(workspace) ? attributes : null;
    }

    private static BasicFileAttributes checkedDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther() || !directory.equals(directory.toRealPath()))
            throw new IOException("原生查询工作区或目录不能通过链接、junction 或别名重定向");
        return attributes;
    }

    private static void verifyDirectory(Path directory, String expectedIdentity) throws IOException {
        if (!expectedIdentity.equals(identity(directory, checkedDirectory(directory))))
            throw new IOException("原生查询目录身份已变化；请重新检查工作区");
    }

    private static boolean hidden(Path path) throws IOException {
        return path.getFileName().toString().startsWith(".") || Files.isHidden(path);
    }

    private static String display(String value) {
        StringBuilder escaped = new StringBuilder();
        value.codePoints().forEach(point -> {
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)
                escaped.append(String.format(Locale.ROOT, "\\u%04x", point));
            else escaped.appendCodePoint(point);
        });
        return escaped.toString();
    }

    private static boolean explicitlyIncludedGithub(Path workspace, Path entry, Query query,
                                                      BasicFileAttributes attributes) {
        return query.search() != null && attributes.isDirectory() && query.fileGlobs().contains(".github/**")
                && workspace.relativize(entry).toString().replace('\\', '/').equals(".github");
    }

    private static String relative(Path workspace, Path path) { return workspace.relativize(path).toString().replace('\\', '/'); }

    /** 正文查询不会因 --hidden 或显式路径而读取常见凭据和私钥；文件名查询不打开这些文件。 */
    private static boolean sensitive(Path workspace, Path path) {
        for (Path component : workspace.relativize(path)) {
            String name = component.toString().toLowerCase(Locale.ROOT);
            if (Set.of(".ssh", ".aws", ".azure", ".gnupg", "secrets", "credentials", "credentials.json",
                    "credentials.yaml", "credentials.yml", "config.local.yaml", ".npmrc", ".pypirc", ".netrc",
                    "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa").contains(name)
                    || name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem")
                    || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx")) return true;
        }
        return false;
    }

    private static void searchFile(Path workspace, Path file, BasicFileAttributes before,
                                   NativeSearchQuery.Spec spec, State state) throws IOException, InterruptedException {
        if (before.size() > MAX_FILE_BYTES) { state.skipped++; return; }
        if (state.bytes + before.size() > MAX_SEARCH_BYTES) { state.truncated = true; state.stopped = true; return; }
        String fileIdentity = identity(file, before);
        List<Path> ancestors = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        for (Path ancestor = file.getParent(); ancestor != null && ancestor.startsWith(workspace); ancestor = ancestor.getParent()) {
            ancestors.add(ancestor);
            identities.add(identity(ancestor, checkedDirectory(ancestor)));
        }
        ByteBuffer bytes = ByteBuffer.allocate((int) before.size() + 1);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining()) {
                if (!state.continueWork()) return;
                int count = channel.read(bytes);
                if (count < 0) break;
                state.bytes += count;
                if (state.bytes > MAX_SEARCH_BYTES) { state.truncated = true; state.stopped = true; return; }
            }
        } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ignored) { state.skipped++; return; }
        for (int index = 0; index < ancestors.size(); index++) verifyDirectory(ancestors.get(index), identities.get(index));
        BasicFileAttributes after = safeAttributes(workspace, file);
        if (after == null || !fileIdentity.equals(identity(file, after)) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || bytes.position() != before.size()) {
            state.skipped++; return;
        }
        String content;
        try {
            bytes.flip();
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        } catch (CharacterCodingException ignored) { state.skipped++; return; }
        if (content.indexOf('\0') >= 0) { state.skipped++; return; }
        int start = 0, line = 1;
        while (start < content.length() && state.continueWork()) {
            int end = content.indexOf('\n', start);
            if (end < 0) end = content.length();
            String value = content.substring(start, end);
            if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
            if (state.matchesLine.test(value))
                state.append(display(relative(workspace, file)) + ":" + (spec.lineNumbers() ? line + ":" : "") + " " + display(value) + "\n");
            start = end + 1;
            line++;
        }
    }

    /** 只匹配已审核过的文件名模式；不把调用方 glob 转换成路径遍历规则。 */
    private static boolean matchesFileGlobs(Path workspace, Path file, Query query) {
        if (!query.ripgrep() && !query.windowsWhere()) return !query.agentsOnly()
                || file.getFileName().toString().equals("AGENTS.md");
        if (query.fileGlobs().isEmpty()) return true;
        String name = file.getFileName().toString();
        String relative = workspace.relativize(file).toString().replace('\\', '/');
        for (String glob : query.fileGlobs()) {
            if (glob.equals(".github/**") && relative.startsWith(".github/")) return true;
            if (query.windowsWhere()
                    ? matchesSimpleGlob(name.toLowerCase(Locale.ROOT), glob.toLowerCase(Locale.ROOT))
                    : matchesSimpleGlob(name, glob)) return true;
        }
        return false;
    }

    private static boolean matchesSimpleGlob(String value, String glob) {
        if (!glob.contains("*")) return value.equals(glob);
        String[] pieces = glob.split("\\*", -1);
        int offset = 0;
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            if (piece.isEmpty()) continue;
            int found = value.indexOf(piece, offset);
            if (found < 0 || index == 0 && !glob.startsWith("*") && found != 0) return false;
            offset = found + piece.length();
        }
        String last = pieces[pieces.length - 1];
        return glob.endsWith("*") || value.endsWith(last);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("原生只读查询已中断");
    }

    private static final class State {
        final Limits limits;
        final NativeSearchQuery.Spec search;
        final long started = System.nanoTime();
        final StringBuilder output = new StringBuilder();
        final Predicate<String> matchesLine;
        final BiPredicate<String, Boolean> matchesPath, excluded;
        final List<Path> roots;
        final List<String> missingRoots = new ArrayList<>();
        final int resultLimit;
        long bytes;
        int skipped;
        int visited, results;
        boolean truncated, stopped;

        State(Limits limits, NativeSearchQuery.Spec search, Path workspace) {
            this.limits = limits;
            this.search = search;
            roots = search == null ? List.of(workspace) : search.paths().stream().map(workspace::resolve).map(Path::normalize).toList();
            resultLimit = search == null ? limits.results() : Math.min(limits.results(), search.maxResults());
            matchesLine = search == null ? value -> false : search.lineMatcher();
            matchesPath = search == null ? (value, directory) -> true : search.pathMatcher();
            excluded = search == null ? (value, directory) -> false : search.exclusionMatcher();
        }
        boolean inScope(Path path) { return roots.stream().anyMatch(root -> path.startsWith(root) || root.startsWith(path)); }
        boolean selectedFile(Path path) { return roots.stream().anyMatch(path::startsWith); }
        boolean continueWork() throws InterruptedException {
            checkInterrupted();
            if (System.nanoTime() - started >= limits.timeout().toNanos()) { truncated = true; stopped = true; }
            return !stopped;
        }
        void append(String line) {
            if (search != null && search.files() && search.stripPrefix() != null
                    && line.startsWith(search.stripPrefix()))
                line = line.substring(search.stripPrefix().length());
            if (results >= resultLimit || output.length() + line.length() > limits.outputChars() - 140) {
                truncated = true;
                stopped = true;
                return;
            }
            results++;
            output.append(line);
        }
        String result() {
            String suffix = (truncated ? "[结果已截断：达到 head/结果、遍历、读取、深度、时间或输出上限]\n" : "")
                    + (skipped > 0 ? "[跳过 " + skipped + " 个过大、二进制、编码无效或读取中变化的文件]\n" : "");
            if (!missingRoots.isEmpty()) suffix += "[未找到路径：" + display(missingRoots.getFirst()) + "; 共 " + missingRoots.size() + " 项]\n";
            int remaining = Math.max(0, limits.outputChars() - output.length());
            return output + (suffix.length() <= remaining ? suffix : suffix.substring(0, remaining));
        }
    }

    private static String identity(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes.fileKey() != null) return attributes.fileKey().toString();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new IOException("文件系统不提供稳定目录身份，不能自动执行原生查询");
        // Windows NIO 可不提供 fileKey；使用卷号和 128 位文件 ID，并禁止句柄跟随重解析点。
        try {
            Pointer handle = WindowsSystem.INSTANCE.CreateFileW(new WString(path.toString()), 0x80, 7,
                    null, 3, 0x02200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1L) throw new IOException("无法读取目录身份");
            try (Memory information = new Memory(24)) {
                if (!WindowsSystem.INSTANCE.GetFileInformationByHandleEx(handle, 18, information, 24))
                    throw new IOException("文件系统不支持目录 ID 查询");
                return HexFormat.of().formatHex(information.getByteArray(0, 24));
            } finally { WindowsSystem.INSTANCE.CloseHandle(handle); }
        } catch (LinkageError error) {
            throw new IOException("无法加载稳定目录身份检查", error);
        }
    }

    private interface WindowsSystem extends StdCallLibrary {
        WindowsSystem INSTANCE = Native.load("kernel32", WindowsSystem.class);
        Pointer CreateFileW(WString filename, int access, int sharing, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer information, int size);
        boolean CloseHandle(Pointer handle);
    }
}
