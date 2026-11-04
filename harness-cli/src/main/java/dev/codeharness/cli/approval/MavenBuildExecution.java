package dev.codeharness.cli.approval;

import dev.codeharness.cli.tools.CommandApproval;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 将常规 Maven 构建绑定到工作区和外部安装；会执行项目代码，不能当成只读查询或沙箱。 */
public final class MavenBuildExecution {
    private static final int MAX_POMS = 128;
    private static final int MAX_PROJECT_FILE_BYTES = 1_000_000;
    private static final long MAX_PROJECT_BYTES = 8_000_000;
    private static final long MAX_RUNTIME_BYTES = 128_000_000;
    private static final Set<String> ENVIRONMENT_HOOKS = Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
            "CLASSPATH", "JVM_CONFIG_MAVEN_PROPS", "JAVACMD");

    record Runtime(Path mavenHome, Path javaHome) {
        Runtime { Objects.requireNonNull(mavenHome); Objects.requireNonNull(javaHome); }
    }

    @FunctionalInterface
    interface RuntimeResolver { Runtime resolve(Path workspace) throws IOException; }

    /** 构造器不公开，调用方不能把未经检查的参数伪装为已审核的构建计划。 */
    public static final class Plan {
        private final Path workspace;
        private final String command;
        private final Runtime runtime;
        private final List<String> arguments;
        private final String fingerprint;
        private final Map<String, Object> reviewData;

        private Plan(Path workspace, String command, Runtime runtime, List<String> arguments,
                     String fingerprint, Map<String, Object> reviewData) {
            this.workspace = workspace;
            this.command = command;
            this.runtime = runtime;
            this.arguments = List.copyOf(arguments);
            this.fingerprint = fingerprint;
            this.reviewData = Map.copyOf(reviewData);
        }

        public Path workspace() { return workspace; }
        public String command() { return command; }
        public List<String> arguments() { return arguments; }
        String fingerprint() { return fingerprint; }
        Map<String, Object> reviewData() { return reviewData; }

        /** 审核结束到实际启动之间也重新检查，变化时不回退成执行原始 shell。 */
        public void revalidate() throws IOException {
            if (!equals(plan(new CommandApproval.Request(workspace, command), runtime)))
                throw new IOException("Maven 构建配置或运行时在审核后发生变化，命令未执行");
        }

        public void configureEnvironment(Map<String, String> environment) {
            environment.keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("MAVEN_")
                    || ENVIRONMENT_HOOKS.contains(name.toUpperCase(Locale.ROOT))
                    || Set.of("JAVA_HOME", "M2_HOME").contains(name.toUpperCase(Locale.ROOT)));
            environment.put("JAVA_HOME", runtime.javaHome().toString());
            environment.put("MAVEN_HOME", runtime.mavenHome().toString());
            environment.put("M2_HOME", runtime.mavenHome().toString());
            environment.put("MAVEN_SKIP_RC", "true");
        }

        @Override public boolean equals(Object other) {
            return other instanceof Plan value && workspace.equals(value.workspace) && command.equals(value.command)
                    && runtime.equals(value.runtime) && arguments.equals(value.arguments) && fingerprint.equals(value.fingerprint)
                    && reviewData.equals(value.reviewData);
        }
        @Override public int hashCode() { return Objects.hash(workspace, command, runtime, arguments, fingerprint); }
    }

    private MavenBuildExecution() {}

    static boolean recognizes(String command) {
        String first = command.stripLeading().split(" +", 2)[0];
        return first.equalsIgnoreCase("mvn") || first.equalsIgnoreCase("mvn.cmd");
    }

    /** 只从调用者的外部安装位置发现 Maven，工作区里的脚本和 mvnw 不进入自动执行。 */
    static Runtime discover(Path workspace) throws IOException {
        var candidates = new LinkedHashSet<Path>();
        String search = System.getenv("PATH");
        if (search != null) for (String entry : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            try {
                String value = entry.strip();
                if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                Path directory = Path.of(value);
                if (value.isBlank() || !directory.isAbsolute()) continue;
                Path marker = directory.resolve(windows() ? "mvn.cmd" : "mvn");
                if (!Files.isRegularFile(marker)) continue;
                Path resolved = marker.toRealPath();
                if (!resolved.startsWith(workspace) && resolved.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    candidates.add(resolved.getParent().getParent());
            } catch (IOException | RuntimeException ignored) { /* 无效 PATH 条目不改变执行范围。 */ }
        }
        for (String name : List.of("MAVEN_HOME", "M2_HOME")) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) continue;
            try { if (Path.of(value).isAbsolute()) candidates.add(Path.of(value).toRealPath()); }
            catch (IOException | RuntimeException ignored) { /* 继续检查其他已配置安装。 */ }
        }
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        String configuredJava = System.getenv("JAVA_HOME");
        if (configuredJava != null && !configuredJava.isBlank()) {
            try {
                Path home = Path.of(configuredJava);
                if (home.isAbsolute() && Files.isRegularFile(home.resolve("bin").resolve(windows() ? "java.exe" : "java")))
                    javaHome = home.toRealPath();
            } catch (IOException | RuntimeException ignored) { /* 未找到有效 JAVA_HOME 时使用启动 ylc 的 Java。 */ }
        }
        for (Path home : candidates) {
            if (!home.startsWith(workspace) && Files.isRegularFile(home.resolve("bin/m2.conf"))
                    && Files.isDirectory(home.resolve("boot")) && Files.isDirectory(home.resolve("lib")))
                return new Runtime(home, javaHome);
        }
        throw blocked("未找到工作区外的标准 Maven 安装；请把 Maven bin 加入 PATH，或设置 MAVEN_HOME");
    }

    static Plan plan(CommandApproval.Request request, Runtime supplied) throws IOException {
        MavenBuildQuery.Query query = MavenBuildQuery.parse(request.command());
        if (query == null) throw blocked("Maven 参数包含未支持的目标、外部配置或执行入口；需要人工确认");
        Path workspace = request.workspace().toAbsolutePath().normalize();
        checked(workspace, true);
        Path home = supplied.mavenHome().toAbsolutePath().normalize();
        Path javaHome = supplied.javaHome().toAbsolutePath().normalize();
        checked(home, true);
        checked(javaHome, true);
        if (home.startsWith(workspace) || javaHome.startsWith(workspace))
            throw blocked("Maven 或 Java 安装位于工作区内部，不能自动执行项目包装器");
        Path java = javaHome.resolve("bin").resolve(windows() ? "java.exe" : "java");
        checked(java, false);
        if (!Files.isExecutable(java)) throw blocked("Java 程序不可执行，需要人工确认");
        Path classworlds;
        checked(home.resolve("boot"), true);
        try (var paths = Files.list(home.resolve("boot"))) {
            var jars = paths.filter(path -> path.getFileName().toString().matches("plexus-classworlds(?:-[^/\\\\]+)?\\.jar"))
                    .sorted().toList();
            if (jars.size() != 1) throw blocked("Maven Classworlds 启动器缺失或不唯一，需要人工确认");
            classworlds = jars.getFirst();
        }
        var snapshot = new Snapshot();
        snapshot.directory(workspace);
        snapshot.directory(home);
        for (String folder : List.of("bin", "boot", "lib", "conf")) snapshot.tree(home.resolve(folder), 0);
        String mavenVersion = mavenVersion(home);
        snapshot.directory(javaHome);
        snapshot.file(java, MAX_RUNTIME_BYTES);
        if (Files.exists(javaHome.resolve("release"), LinkOption.NOFOLLOW_LINKS)) snapshot.file(javaHome.resolve("release"), 65_536);
        Project project = new Project(workspace, snapshot);
        project.pom(workspace.resolve("pom.xml"), 0);
        project.checkSelectedModules(query.modules());
        Path mavenDirectory = workspace.resolve(".mvn");
        List<String> configurationArguments = List.of(), jvmArguments = List.of();
        List<String> extensions = List.of();
        if (Files.exists(mavenDirectory, LinkOption.NOFOLLOW_LINKS)) {
            snapshot.projectTree(mavenDirectory, 0);
            configurationArguments = configurationArguments(snapshot.projectContents.get(mavenDirectory.resolve("maven.config")));
            jvmArguments = jvmArguments(snapshot.projectContents.get(mavenDirectory.resolve("jvm.config")));
            Path extensionFile = mavenDirectory.resolve("extensions.xml");
            if (Files.exists(extensionFile, LinkOption.NOFOLLOW_LINKS)) {
                Element document = xml(snapshot.projectContents.get(extensionFile));
                extensions = children(document, "extension").stream().map(MavenBuildExecution::coordinates).toList();
                if (extensions.size() > 32) throw blocked("Maven 扩展数量超过审核边界，需要人工确认");
            }
        }
        var effective = new ArrayList<>(configurationArguments);
        effective.addAll(query.arguments());
        MavenBuildQuery.Query combined = MavenBuildQuery.parse(render(effective));
        if (combined == null) throw blocked("项目 .mvn/maven.config 与命令参数组合无法可靠审核，需要人工确认");
        project.checkSelectedModules(combined.modules());
        var allExtensions = new LinkedHashSet<>(project.extensions);
        allExtensions.addAll(extensions);
        var arguments = new ArrayList<String>();
        arguments.add(java.toString());
        arguments.addAll(jvmArguments);
        // 跟随启动 ylc 的用户目录，避免嵌入运行或显式 JVM 用户目录在子 Maven 中悄悄丢失。
        arguments.add("-Duser.home=" + Path.of(System.getProperty("user.home")).toAbsolutePath().normalize());
        arguments.addAll(List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-classpath", classworlds.toString(), "-Dclassworlds.conf=" + home.resolve("bin/m2.conf"),
                "-Dmaven.home=" + home, "-Dmaven.conf=" + home.resolve("conf"),
                "-Dlibrary.jansi.path=" + home.resolve("lib/jansi-native"),
                "-Dmaven.multiModuleProjectDirectory=" + workspace, "org.codehaus.plexus.classworlds.launcher.Launcher",
                "-B", "-Dstyle.color=never"));
        arguments.addAll(query.arguments());
        var data = new LinkedHashMap<String, Object>();
        data.put("goals", combined.goals());
        data.put("mavenVersion", mavenVersion);
        data.put("modules", combined.modules());
        data.put("profiles", combined.profiles());
        data.put("offline", combined.offline());
        data.put("projectOptions", configurationArguments);
        data.put("jvmOptions", jvmArguments);
        data.put("pomCount", project.poms.size());
        data.put("plugins", List.copyOf(project.plugins));
        data.put("extensions", List.copyOf(allExtensions));
        data.put("parentResolution", "沿用 Maven 父 POM 和依赖解析；未读取或发送工作区外父 POM、用户 settings/toolchains 正文");
        data.put("sideEffects", "会执行 POM 插件、扩展和测试代码，可写构建输出与 Maven 本地缓存并联网。"
                + "offline 仅限制 Maven 下载依赖，插件和测试代码仍可自行联网。"
                + "进程拥有当前用户权限，不是沙箱；项目代码及用户 Maven 配置的行为不由参数解析器隔离。");
        return new Plan(workspace, request.command(), new Runtime(home, javaHome), arguments, snapshot.finish(), data);
    }

    private static List<String> configurationArguments(byte[] bytes) throws IOException {
        if (bytes == null) return List.of();
        if (bytes.length > 16_384) throw blocked("项目 .mvn/maven.config 超过参数检查边界，需要人工确认");
        var arguments = new ArrayList<String>();
        String text = utf8(bytes);
        for (String line : text.split("\\r?\\n", -1)) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (value.indexOf('\'') >= 0 || value.indexOf('"') >= 0)
                throw blocked("项目 .mvn/maven.config 含引号歧义，需要人工确认");
            // Maven 3.9 的配置文件每行一个参数；不把一行里的 shell 风格空格重新拆分。
            arguments.add(value);
        }
        var check = new ArrayList<>(arguments);
        check.add("validate");
        MavenBuildQuery.Query parsed = MavenBuildQuery.parse(render(check));
        if (parsed == null || !parsed.goals().equals(List.of("validate")))
            throw blocked("项目 .mvn/maven.config 含未支持的目标或配置入口，需要人工确认");
        return List.copyOf(arguments);
    }

    private static List<String> jvmArguments(byte[] bytes) throws IOException {
        if (bytes == null) return List.of();
        if (bytes.length > 4096) throw blocked("项目 .mvn/jvm.config 超过参数检查边界，需要人工确认");
        String value = utf8(bytes).strip();
        if (value.isEmpty()) return List.of();
        var arguments = List.of(value.split("\\s+"));
        if (arguments.size() > 16 || arguments.stream().anyMatch(argument ->
                !argument.matches("-X(?:ms|mx|ss)[1-9][0-9]{0,8}[kKmMgG]?")
                        && !argument.matches("-XX:(?:MaxMetaspaceSize|ReservedCodeCacheSize)=[1-9][0-9]{0,8}[kKmMgG]?")
                        && !argument.matches("-XX:ActiveProcessorCount=[1-9][0-9]{0,2}")))
            throw blocked("项目 .mvn/jvm.config 含未支持的 JVM 设置或代码加载入口，需要人工确认");
        return arguments;
    }

    private static String render(List<String> arguments) { return "mvn " + String.join(" ", arguments.stream().map(value -> "'" + value + "'").toList()); }

    /** Maven 3.9 起按行读取 maven.config；不同主版本不能复用这一语义假设。 */
    private static String mavenVersion(Path home) throws IOException {
        try (var files = Files.list(home.resolve("lib"))) {
            var cores = files.filter(file -> file.getFileName().toString().matches("maven-core-[^/\\\\]+\\.jar")).limit(2).toList();
            if (cores.size() != 1) throw blocked("无法确认 Maven 版本；自动构建需要标准 Maven 3.9.x 安装");
            try (var jar = new java.util.zip.ZipFile(cores.getFirst().toFile())) {
                var entry = jar.getEntry("META-INF/maven/org.apache.maven/maven-core/pom.properties");
                if (entry == null || entry.getSize() > 8192) throw blocked("无法确认 Maven 版本；自动构建需要 Maven 3.9.x");
                byte[] content;
                try (var stream = jar.getInputStream(entry)) { content = stream.readNBytes(8193); }
                if (content.length > 8192) throw blocked("Maven 版本信息超过检查边界，需要人工确认");
                var properties = new java.util.Properties();
                properties.load(new ByteArrayInputStream(content));
                String version = properties.getProperty("version", "");
                if (!version.matches("3\\.9\\.[0-9]+")) throw blocked("自动构建目前需要 Maven 3.9.x，以保证配置解析一致；其他版本需人工确认");
                return version;
            }
        }
    }

    private static final class Project {
        final Path workspace;
        final Snapshot snapshot;
        final Set<Path> poms = new LinkedHashSet<>();
        final Set<String> artifacts = new LinkedHashSet<>();
        final Set<String> plugins = new LinkedHashSet<>();
        final Set<String> extensions = new LinkedHashSet<>();
        Project(Path workspace, Snapshot snapshot) { this.workspace = workspace; this.snapshot = snapshot; }

        void pom(Path file, int depth) throws IOException {
            checkInterrupted();
            if (poms.contains(file)) return;
            if (!file.startsWith(workspace) || depth > 16 || poms.size() >= MAX_POMS)
                throw blocked("Maven 模块路径越界或项目规模超过配置审核边界，需要人工确认");
            snapshot.directory(file.getParent());
            byte[] bytes = snapshot.projectFile(file);
            poms.add(file);
            Element project = xml(bytes);
            String artifact = text(project, "artifactId");
            if (!artifact.isBlank()) artifacts.add(artifact);
            for (Element build : children(project, "build")) plugins(build);
            var moduleContainers = new ArrayList<>(children(project, "modules"));
            for (Element profiles : children(project, "profiles")) for (Element profile : children(profiles, "profile")) {
                moduleContainers.addAll(children(profile, "modules"));
                for (Element build : children(profile, "build")) plugins(build);
            }
            for (Element modules : moduleContainers) for (Element module : children(modules, "module")) {
                String name = module.getTextContent().strip();
                Path path = projectPath(file.getParent(), name);
                Path child = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path.resolve("pom.xml") : path;
                if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS))
                    throw blocked("Maven 声明的模块 POM 不存在或无法绑定，需要人工确认");
                pom(child, depth + 1);
            }
            for (Element parent : children(project, "parent")) {
                List<Element> paths = children(parent, "relativePath");
                String value = paths.isEmpty() ? "../pom.xml" : paths.getFirst().getTextContent().strip();
                if (value.isEmpty()) continue;
                Path parentFile;
                try { parentFile = file.getParent().resolve(value).normalize(); }
                catch (RuntimeException invalid) { throw blocked("Maven 父 POM 路径无法识别，需要人工确认"); }
                // 外部依赖/父 POM 沿用正常 Maven 行为；检查器不去读取工作区之外的正文。
                if (parentFile.startsWith(workspace) && Files.exists(parentFile, LinkOption.NOFOLLOW_LINKS)) pom(parentFile, depth + 1);
            }
        }

        private Path projectPath(Path base, String relative) throws IOException {
            try {
                Path supplied = Path.of(relative);
                Path resolved = base.resolve(supplied).normalize();
                if (relative.isBlank() || relative.contains("${") || supplied.isAbsolute() || !resolved.startsWith(workspace))
                    throw blocked("Maven 模块路径不是已知的工作区内路径，需要人工确认");
                return resolved;
            } catch (RuntimeException invalid) { throw blocked("Maven 模块路径无法识别，需要人工确认"); }
        }

        void checkSelectedModules(List<String> modules) throws IOException {
            for (String module : modules) {
                if (module.startsWith(":")) {
                    if (!artifacts.contains(module.substring(1))) throw blocked("Maven 指定的 artifactId 未匹配已绑定模块，需要人工确认");
                    continue;
                }
                Path path = projectPath(workspace, module);
                Path file = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path.resolve("pom.xml") : path;
                pom(file, 0);
            }
        }

        private void plugins(Element build) throws IOException {
            for (Element list : children(build, "extensions")) for (Element extension : children(list, "extension")) {
                extensions.add(coordinates(extension));
                if (extensions.size() > 32) throw blocked("Maven 扩展数量超过审核边界，需要人工确认");
            }
            var containers = new ArrayList<>(children(build, "plugins"));
            for (Element management : children(build, "pluginManagement")) containers.addAll(children(management, "plugins"));
            for (Element container : containers) for (Element plugin : children(container, "plugin")) {
                String entry = coordinates(plugin);
                var executions = new ArrayList<String>();
                for (Element list : children(plugin, "executions")) for (Element execution : children(list, "execution")) {
                    for (Element goals : children(execution, "goals")) for (Element goal : children(goals, "goal"))
                        executions.add(bounded(text(execution, "phase")) + ":" + bounded(goal.getTextContent().strip()));
                }
                plugins.add(entry + (executions.isEmpty() ? "" : " [" + String.join(",", executions) + "]"));
                if (plugins.size() > 96 || executions.size() > 32) throw blocked("Maven 插件投影超过审核边界，需要人工确认");
            }
        }
    }

    private static final class Snapshot {
        final MessageDigest digest = digest();
        final Map<Path, byte[]> projectContents = new LinkedHashMap<>();
        int runtimeEntries, projectEntries;
        long runtimeBytes, projectBytes;

        void directory(Path path) throws IOException {
            BasicFileAttributes attributes = checked(path, true);
            add("directory", path.toString(), String.valueOf(ReadOnlyCommandGate.identity(path, attributes)));
        }
        void tree(Path path, int depth) throws IOException {
            checkInterrupted();
            if (++runtimeEntries > 2048 || depth > 12) throw blocked("Maven 安装规模超过运行时检查边界，需要人工确认");
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { file(path, MAX_RUNTIME_BYTES); return; }
            directory(path);
            try (var paths = Files.list(path)) { for (Path child : paths.limit(2049).sorted().toList()) tree(child, depth + 1); }
        }
        void projectTree(Path path, int depth) throws IOException {
            checkInterrupted();
            if (++projectEntries > 128 || depth > 8) throw blocked(".mvn 配置数量超过审核边界，需要人工确认");
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { projectFile(path); return; }
            directory(path);
            try (var paths = Files.list(path)) { for (Path child : paths.limit(129).sorted().toList()) projectTree(child, depth + 1); }
        }
        byte[] projectFile(Path path) throws IOException {
            byte[] bytes = read(path, MAX_PROJECT_FILE_BYTES);
            if ((projectBytes += bytes.length) > MAX_PROJECT_BYTES) throw blocked("Maven 项目配置超过大小边界，需要人工确认");
            bind(path, bytes);
            projectContents.put(path, bytes);
            return bytes;
        }
        void file(Path path, long maximum) throws IOException {
            byte[] bytes = read(path, maximum);
            if ((runtimeBytes += bytes.length) > MAX_RUNTIME_BYTES) throw blocked("Maven 运行时超过大小边界，需要人工确认");
            bind(path, bytes);
        }
        private void bind(Path path, byte[] bytes) throws IOException {
            BasicFileAttributes attributes = checked(path, false);
            add("file", path.toString(), String.valueOf(ReadOnlyCommandGate.identity(path, attributes)),
                    Long.toString(attributes.size()), attributes.lastModifiedTime().toString());
            digest.update(bytes);
            digest.update((byte) 0);
        }
        private void add(String... values) { for (String value : values) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); } }
        String finish() { return HexFormat.of().formatHex(digest.digest()); }
    }

    private static BasicFileAttributes checked(Path path, boolean directory) throws IOException {
        checkInterrupted();
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.equals(path.toRealPath()))
            throw blocked("Maven 配置或运行时路径经过链接、junction 或重定向，需要人工确认");
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw blocked("Maven 路径含符号链接，需要人工确认");
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) throw blocked("Maven 配置或运行时不是普通文件/目录，需要人工确认");
        return attributes;
    }

    private static byte[] read(Path file, long maximum) throws IOException {
        checkInterrupted();
        BasicFileAttributes before = checked(file, false);
        Object beforeIdentity = ReadOnlyCommandGate.identity(file, before);
        if (before.size() > maximum) throw blocked("Maven 配置或运行时文件超过检查边界，需要人工确认");
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
             var output = new java.io.ByteArrayOutputStream((int) Math.min(before.size(), 65_536))) {
            byte[] buffer = new byte[65_536];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                checkInterrupted();
                if ((long) output.size() + count > maximum) throw blocked("Maven 文件超过检查大小边界，需要人工确认");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        BasicFileAttributes after = checked(file, false);
        if (bytes.length > maximum || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !beforeIdentity.equals(ReadOnlyCommandGate.identity(file, after)))
            throw blocked("Maven 文件在检查期间发生变化，需要人工确认");
        return bytes;
    }

    private static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw blocked("Maven 配置不是有效 UTF-8，需要人工确认"); }
    }

    private static Element xml(byte[] bytes) throws IOException {
        checkInterrupted();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception invalid) { throw blocked("Maven XML 配置无法可靠解析或包含外部实体，需要人工确认"); }
    }

    private static List<Element> children(Element element, String name) {
        var result = new ArrayList<Element>();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element child && child.getTagName().equals(name)) result.add(child);
        return result;
    }
    private static String text(Element element, String name) { var values = children(element, name); return values.isEmpty() ? "" : values.getFirst().getTextContent().strip(); }
    private static String coordinates(Element element) { return bounded(text(element, "groupId")) + ":" + bounded(text(element, "artifactId")) + ":" + bounded(text(element, "version")); }
    private static String bounded(String value) { return value.matches("[A-Za-z0-9._${}:-]{0,120}") ? value : "[未展开的配置值]"; }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Maven 构建复核已取消，命令未执行");
    }
    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static IOException blocked(String reason) { return new Ineligible(reason); }

    static final class Ineligible extends IOException { Ineligible(String reason) { super(reason); } }
}
