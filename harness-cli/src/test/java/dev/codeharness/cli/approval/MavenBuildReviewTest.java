package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.cli.tools.WorkspaceTools;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Maven 构建经 AI 审核后执行固定 Java 参数，所有执行都使用本地无副作用的假 Launcher。 */
class MavenBuildReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String YES = "{\"decision\":\"approve\"}";
    private static final String BUILD = "mvn -o -B test";
    private static final String LONG_BUILD = "mvn -o -pl soulclaw-knowledge-server -am -DskipTests=false "
            + "-Dtest=ApiKeyFilterTest,MarkdownPathResolverTest -Dsurefire.failIfNoSpecifiedTests=false test";
    @TempDir Path temporary;

    @Test void 长测试命令进入无工具审核且明确说明构建副作用() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Files.createDirectories(fixture.workspace.resolve("soulclaw-knowledge-server"));
        Files.writeString(fixture.workspace.resolve("soulclaw-knowledge-server/pom.xml"), MavenBuildTestFixture.pom("server"));
        var received = new AtomicReference<ChatRequest>();
        var review = reviewer(fixture, request -> { received.set(request); return ChatResponse.text(YES); });
        review.context(List.of(ChatMessage.user("请验证当前改动并运行相关 Maven 测试"),
                ChatMessage.assistant("助手内容不能替代用户的运行授权")));

        assertTrue(LONG_BUILD.length() > 128);
        var outcome = review.review(request(fixture, LONG_BUILD));

        assertTrue(outcome.approved(), outcome.reason());
        assertEquals(CommandReview.Status.AI_APPROVED, outcome.status());
        assertNotNull(outcome.mavenBuild());
        assertTrue(outcome.arguments().isEmpty());
        assertNull(outcome.nativeQuery());
        assertEquals(outcome.mavenBuild(), outcome.decision().mavenBuild());
        assertEquals(fixture.workspace, outcome.mavenBuild().workspace());
        assertEquals(LONG_BUILD, outcome.mavenBuild().command());
        ChatRequest sent = received.get();
        assertNotNull(sent);
        assertTrue(sent.tools().isEmpty());
        assertEquals(2, sent.messages().size());
        JsonNode data = JSON.readTree(sent.messages().getLast().content());
        assertEquals(LONG_BUILD, data.path("command").asText());
        assertEquals("maven-build", data.path("actionType").asText());
        assertTrue(data.path("build").isObject());
        assertTrue(data.path("context").toString().contains("运行相关 Maven 测试"));
        assertFalse(data.toString().contains("助手内容不能替代"));
        String meaning = sent.messages().getFirst().content() + data;
        assertTrue(meaning.contains("Maven"), meaning);
        assertTrue(meaning.contains("插件") && meaning.contains("测试"), meaning);
        assertTrue(meaning.contains("联网") && meaning.contains("缓存"), meaning);
        assertEquals(1, review.stats().requests());
        assertEquals(0, review.stats().localApprovals());
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)), "审核阶段不得执行构建");
    }

    @Test void AI批准后WorkspaceTools直接启动绑定Java且不执行工作区Maven包装器() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Files.writeString(fixture.workspace.resolve("mvn.cmd"), "@echo unsafe>workspace-wrapper-ran.txt\n");
        var outcome = reviewer(fixture, request -> ChatResponse.text(YES)).review(request(fixture, BUILD));
        assertTrue(outcome.approved(), outcome.reason());
        List<String> arguments = outcome.mavenBuild().arguments();
        assertEquals(fixture.javaExecutable().toRealPath(), Path.of(arguments.getFirst()).toRealPath());
        assertTrue(arguments.contains(MavenBuildTestFixture.MAIN_CLASS));
        assertTrue(arguments.stream().anyMatch(value -> value.equals("-Dmaven.home=" + fixture.mavenHome)));
        assertFalse(arguments.contains("/c"));
        assertFalse(arguments.stream().anyMatch(value -> value.endsWith("mvn.cmd")));
        int launcher = arguments.indexOf(MavenBuildTestFixture.MAIN_CLASS);

        String output = execute(fixture, BUILD, outcome.decision());

        assertTrue(output.startsWith("退出码：0\n"), output);
        assertTrue(output.contains("MAVEN_FIXTURE_COMPLETE"), output);
        assertEquals(arguments.subList(launcher + 1, arguments.size()), decodedArguments(output));
        assertEquals(fixture.mavenHome.toString(), decodedProperty(output, "maven.home"));
        assertEquals(fixture.workspace.toString(), decodedProperty(output, "maven.multiModuleProjectDirectory"));
        assertTrue(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
        assertFalse(Files.exists(fixture.workspace.resolve("workspace-wrapper-ran.txt")));
        assertFalse(Files.exists(fixture.workspace.resolve("maven-wrapper-executed.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"decision\":\"manual\"}", "{\"decision\":\"approve\"} trailing", "PROVIDER_FAILURE"})
    void 人工结论无效答复和厂商异常均不能执行Maven(String reply) throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var review = reviewer(fixture, request -> {
            if (reply.equals("PROVIDER_FAILURE")) throw new IllegalStateException("fixture-private-provider-error");
            return ChatResponse.text(reply);
        });

        var outcome = review.review(request(fixture, BUILD));

        assertFalse(outcome.approved());
        assertNull(outcome.mavenBuild());
        assertEquals(1, review.stats().requests());
        assertEquals(0, review.stats().approved());
        assertFalse(outcome.reason().contains("fixture-private-provider-error"));
        assertThrows(SecurityException.class, () -> execute(fixture, BUILD, outcome.decision()));
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void 审核过程中POM变化会撤销返回的允许结果() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var review = reviewer(fixture, request -> {
            try { Files.writeString(fixture.workspace.resolve("pom.xml"), MavenBuildTestFixture.pom("changed")); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            return ChatResponse.text(YES);
        });

        var outcome = review.review(request(fixture, BUILD));

        assertFalse(outcome.approved());
        assertEquals(CommandReview.Status.CONDITIONS_CHANGED, outcome.status());
        assertNull(outcome.mavenBuild());
        assertEquals(0, review.stats().approved());
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void 审核过程中Maven运行时变化会撤销返回的允许结果() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var review = reviewer(fixture, request -> {
            try { Files.writeString(fixture.mavenHome.resolve("bin/m2.conf"), "changed-runtime\n"); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            return ChatResponse.text(YES);
        });

        var outcome = review.review(request(fixture, BUILD));

        assertFalse(outcome.approved());
        assertEquals(0, review.stats().approved());
        assertNull(outcome.mavenBuild());
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void POM改变不能命中之前批准的构建缓存() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        AtomicInteger calls = new AtomicInteger();
        var review = reviewer(fixture, request -> { calls.incrementAndGet(); return ChatResponse.text(YES); });
        var first = review.review(request(fixture, BUILD));
        assertTrue(first.approved(), first.reason());
        var cached = review.review(request(fixture, BUILD));
        assertTrue(cached.approved(), cached.reason());
        assertEquals(1, calls.get());
        int oldCacheHits = review.stats().cacheHits();

        Files.writeString(fixture.workspace.resolve("pom.xml"), MavenBuildTestFixture.pom("next-version"));
        var changed = review.review(request(fixture, BUILD));

        assertNotEquals(CommandReview.Status.CACHED_APPROVED, changed.status());
        assertEquals(oldCacheHits, review.stats().cacheHits());
        assertEquals(2, calls.get(), "配置改变后应重新请求审核，不能沿用旧批准");
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void 已批准计划在执行前再次拒绝变更的POM() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var outcome = reviewer(fixture, request -> ChatResponse.text(YES)).review(request(fixture, BUILD));
        assertTrue(outcome.approved(), outcome.reason());
        Files.writeString(fixture.workspace.resolve("pom.xml"), MavenBuildTestFixture.pom("changed-before-execution"));

        assertThrows(Exception.class, () -> execute(fixture, BUILD, outcome.decision()));

        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void Maven安装位于工作区内部时不能交审核模型放行() throws Exception {
        Path outer = Files.createDirectories(temporary.resolve("workspace")).toRealPath();
        var fixture = MavenBuildTestFixture.create(outer);
        AtomicInteger calls = new AtomicInteger();
        var gate = new ReadOnlyCommandGate(ignored -> fixture.runtime());
        var review = new CommandReview(request -> { calls.incrementAndGet(); return ChatResponse.text(YES); },
                Duration.ofSeconds(2), 8, ignored -> {}, ignored -> {}, gate);
        Files.writeString(outer.resolve("pom.xml"), MavenBuildTestFixture.pom("outer"));

        var outcome = review.review(new CommandApproval.Request(outer, BUILD));

        assertFalse(outcome.approved());
        assertEquals(0, calls.get());
        assertNull(outcome.mavenBuild());
    }

    @Test void 绑定Maven计划不能用于另一命令或工作区() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var outcome = reviewer(fixture, request -> ChatResponse.text(YES)).review(request(fixture, BUILD));
        assertTrue(outcome.approved(), outcome.reason());
        assertThrows(SecurityException.class, () -> execute(fixture, "mvn -o -B verify", outcome.decision()));
        Path other = Files.createDirectories(temporary.resolve("other")).toRealPath();
        var tools = WorkspaceTools.createWithCommandApproval(other, ignored -> outcome.decision());
        assertThrows(SecurityException.class,
                () -> tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", BUILD))));
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
        assertFalse(Files.exists(other.resolve(MavenBuildTestFixture.MARKER)));
    }

    @Test void 固定Java启动会清理Maven与JVM注入环境() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var arguments = new ArrayList<>(List.of(fixture.javaExecutable().toString(), "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-classpath", classpath,
                MavenBuildTestFixture.class.getName(), fixture.workspace.toString(), fixture.mavenHome.toString(), fixture.javaHome.toString()));
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        builder.directory(temporary.toFile());
        for (String name : MavenBuildTestFixture.HOOKS) builder.environment().put(name, "fixture-hook-must-be-removed");
        // 这些值只给测试宿主增加无害属性；真正的 Maven 子 JVM 必须清理它们。
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Dfixture.javaTool=true");
        builder.environment().put("JDK_JAVA_OPTIONS", "-Dfixture.jdkJava=true");
        builder.environment().put("_JAVA_OPTIONS", "-Dfixture.legacyJava=true");
        Path log = temporary.resolve("environment-fixture.log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished;
        try { finished = process.waitFor(30, TimeUnit.SECONDS); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        for (String name : MavenBuildTestFixture.HOOKS) assertTrue(output.contains("HOOK=" + name + ":absent"), output);
        for (String name : List.of("fixture.javaTool", "fixture.jdkJava", "fixture.legacyJava"))
            assertTrue(output.contains("INJECTED=" + name + ":absent"), output);
        assertTrue(output.contains("MAVEN_FIXTURE_COMPLETE"), output);
        assertTrue(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    private static CommandReview reviewer(MavenBuildTestFixture fixture, ChatModel model) {
        var gate = new ReadOnlyCommandGate(ignored -> fixture.runtime());
        var review = new CommandReview(model, Duration.ofSeconds(2), 8, ignored -> {}, ignored -> {}, gate);
        review.context(List.of(ChatMessage.user("请运行 Maven 测试验证当前修改")));
        return review;
    }

    private static CommandApproval.Request request(MavenBuildTestFixture fixture, String command) {
        return new CommandApproval.Request(fixture.workspace, command);
    }

    private static String execute(MavenBuildTestFixture fixture, String command, CommandApproval.Decision decision) throws Exception {
        var tools = WorkspaceTools.createWithCommandApproval(fixture.workspace, ignored -> decision);
        return tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", command)));
    }

    private static List<String> decodedArguments(String output) {
        return output.lines().filter(line -> line.startsWith("ARG="))
                .map(line -> new String(Base64.getDecoder().decode(line.substring(4)), StandardCharsets.UTF_8)).toList();
    }

    private static String decodedProperty(String output, String name) {
        String prefix = "PROPERTY=" + name + ":";
        return output.lines().filter(line -> line.startsWith(prefix))
                .map(line -> new String(Base64.getDecoder().decode(line.substring(prefix.length())), StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new AssertionError("未收到固定属性：" + name));
    }
}
