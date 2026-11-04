package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.cli.tools.WorkspaceTools;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatResponse;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** 只输出参数与环境状态的 Maven 运行时夹具，不读取 Maven 配置、不执行插件或测试。 */
final class MavenBuildTestFixture {
    static final List<String> HOOKS = List.of("MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_DEBUG_OPTS", "MAVEN_PROJECTBASEDIR",
            "MAVEN_BASEDIR", "MAVEN_CONFIG", "MAVEN_CMD_LINE_ARGS", "JVM_CONFIG_MAVEN_PROPS", "JAVACMD",
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH");
    static final String MARKER = "maven-fixture-executed.txt";
    static final String MAIN_CLASS = "org.codehaus.plexus.classworlds.launcher.Launcher";
    final Path workspace;
    final Path mavenHome;
    final Path javaHome;

    private MavenBuildTestFixture(Path workspace, Path mavenHome, Path javaHome) {
        this.workspace = workspace;
        this.mavenHome = mavenHome;
        this.javaHome = javaHome;
    }

    static MavenBuildTestFixture create(Path base) throws IOException {
        Path workspace = Files.createDirectories(base.resolve("workspace")).toRealPath();
        Path home = Files.createDirectories(base.resolve("maven-home")).toRealPath();
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("boot"));
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("bin/mvn"), "#!/bin/sh\nprintf wrapper-must-not-run\n", StandardCharsets.UTF_8);
        Files.writeString(home.resolve("bin/mvn.cmd"), "@echo wrapper-must-not-run>maven-wrapper-executed.txt\n", StandardCharsets.UTF_8);
        Files.writeString(home.resolve("bin/m2.conf"), "main is org.apache.maven.cli.MavenCli from plexus.core\n"
                + "set maven.conf default ${maven.home}/conf\n[plexus.core]\nload ${maven.home}/lib/*.jar\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("pom.xml"), pom("first"), StandardCharsets.UTF_8);
        writeVersion(home.resolve("lib/maven-core-3.9.14.jar"), "3.9.14");
        buildLauncher(base, home.resolve("boot/plexus-classworlds-fake.jar"));
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        return new MavenBuildTestFixture(workspace, home, javaHome);
    }

    static String pom(String artifactId) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>fixture</groupId><artifactId>" + artifactId + "</artifactId><version>1</version></project>\n";
    }

    MavenBuildExecution.Runtime runtime() { return new MavenBuildExecution.Runtime(mavenHome, javaHome); }

    Path javaExecutable() { return javaHome.resolve("bin").resolve(windows() ? "java.exe" : "java"); }

    /** 文件名保持不变，版本测试验证真正读取 JAR 元数据而不是猜测文件名。 */
    void mavenVersion(String version) throws IOException {
        writeVersion(mavenHome.resolve("lib/maven-core-3.9.14.jar"), version);
    }

    private static void writeVersion(Path jar, String version) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/maven/org.apache.maven/maven-core/pom.properties"));
            output.write(("version=" + version + "\n").getBytes(StandardCharsets.ISO_8859_1));
            output.closeEntry();
        }
    }

    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    private static void buildLauncher(Path base, Path jar) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Maven 审核夹具需要 JDK 编译器");
        Path source = base.resolve("source/org/codehaus/plexus/classworlds/launcher/Launcher.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.codehaus.plexus.classworlds.launcher;
                public final class Launcher {
                    public static void main(String[] arguments) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("maven-fixture-executed.txt"), "executed");
                        for (String argument : arguments) System.out.println("ARG=" + encode(argument));
                        for (String name : java.util.List.of("MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_DEBUG_OPTS",
                                "MAVEN_PROJECTBASEDIR", "MAVEN_BASEDIR", "MAVEN_CONFIG", "MAVEN_CMD_LINE_ARGS",
                                "JVM_CONFIG_MAVEN_PROPS", "JAVACMD", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                                "_JAVA_OPTIONS", "CLASSPATH"))
                            System.out.println("HOOK=" + name + ":" + (System.getenv(name) == null ? "absent" : "present"));
                        for (String name : java.util.List.of("maven.home", "maven.multiModuleProjectDirectory", "java.home"))
                            System.out.println("PROPERTY=" + name + ":" + encode(System.getProperty(name, "")));
                        for (String name : java.util.List.of("fixture.javaTool", "fixture.jdkJava", "fixture.legacyJava"))
                            System.out.println("INJECTED=" + name + ":" + (System.getProperty(name) == null ? "absent" : "present"));
                        System.out.println("MAVEN_FIXTURE_COMPLETE");
                    }
                    private static String encode(String value) {
                        return java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                """, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(base.resolve("classes"));
        var diagnostics = new ByteArrayOutputStream();
        int code = compiler.run(null, diagnostics, diagnostics, "--release", "21", "-encoding", "UTF-8",
                "-d", classes.toString(), source.toString());
        if (code != 0) throw new IOException("无法编译 Maven 夹具：" + diagnostics.toString(StandardCharsets.UTF_8));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            String classFile = "org/codehaus/plexus/classworlds/launcher/Launcher.class";
            output.putNextEntry(new JarEntry(classFile));
            Files.copy(classes.resolve(classFile), output);
            output.closeEntry();
        }
    }

    /** 仅测试用的独立宿主进程，让受审执行器真正处理受污染的环境，而不修改测试 JVM。 */
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toRealPath();
        var runtime = new MavenBuildExecution.Runtime(Path.of(args[1]), Path.of(args[2]));
        var gate = new ReadOnlyCommandGate(ignored -> runtime);
        var review = new CommandReview(request -> ChatResponse.text("{\"decision\":\"approve\"}"),
                Duration.ofSeconds(2), 8, ignored -> {}, ignored -> {}, gate);
        review.context(List.of(ChatMessage.user("请运行 Maven 测试验证当前修改")));
        String command = "mvn -o -B test";
        var outcome = review.review(new CommandApproval.Request(workspace, command));
        if (!outcome.approved() || outcome.mavenBuild() == null)
            throw new AssertionError("夹具命令未进入 Maven 审核计划：" + outcome.reason());
        var tools = WorkspaceTools.createWithCommandApproval(workspace, ignored -> outcome.decision());
        String result = tools.find("run_command").orElseThrow().execute(new ObjectMapper().writeValueAsString(Map.of("command", command)));
        System.out.println(result);
        if (!result.startsWith("退出码：0\n") || !result.contains("MAVEN_FIXTURE_COMPLETE"))
            throw new AssertionError("Maven 夹具执行失败");
    }
}
