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
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 配置解析、版本与批准后复核的边界；假运行时不会执行真实 Maven 或联网。 */
class MavenBuildExecutionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUILD = "mvn -o -B test";
    private static final String YES = "{\"decision\":\"approve\"}";
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"3.8.8", "4.0.0-rc-5", "3.9.14-SNAPSHOT", ""})
    void 版本元数据不是稳定Maven39时不调用审核模型(String version) throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        fixture.mavenVersion(version);

        var outcome = refusedWithoutModel(fixture);

        assertTrue(outcome.reason().contains("3.9.x"), outcome.reason());
    }

    @Test void 逐行Maven参数保留单个属性中的空格并生成完整审核投影() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Files.createDirectory(fixture.workspace.resolve("server"));
        Files.writeString(fixture.workspace.resolve("server/pom.xml"), MavenBuildTestFixture.pom("server"));
        config(fixture, "maven.config", "# 每行一个参数\r\n\r\n-o\r\n-pl\r\nserver\r\n-Pci\r\n-Dtest=Selected One\r\n");
        var received = new AtomicReference<ChatRequest>();
        var review = reviewer(fixture, request -> { received.set(request); return ChatResponse.text(YES); });

        var outcome = review.review(new CommandApproval.Request(fixture.workspace, "mvn test"));

        assertTrue(outcome.approved(), outcome.reason());
        JsonNode build = projectedBuild(received.get());
        assertEquals("3.9.14", build.path("mavenVersion").asText());
        assertTrue(build.path("offline").asBoolean());
        assertEquals(JSON.valueToTree(List.of("-o", "-pl", "server", "-Pci", "-Dtest=Selected One")), build.path("projectOptions"));
        assertEquals(JSON.valueToTree(List.of("server")), build.path("modules"));
        assertEquals(JSON.valueToTree(List.of("ci")), build.path("profiles"));
        assertEquals(JSON.valueToTree(List.of("test")), build.path("goals"));
        assertEquals(2, build.path("pomCount").asInt());
        assertNoExecution(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-o -B\n", "-s\noutside.xml\n", "-DargLine=-javaagent:evil.jar\n",
            "-Dnote='quoted'\n", "clean\n", "--offline\n& echo unsafe\n",
            "-pl\n../external\n", "-Dnote=${env.SECRET}\n"
    })
    void 项目Maven配置中的目标注入或参数歧义不能交给AI放行(String value) throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        config(fixture, "maven.config", value);

        refusedWithoutModel(fixture);
    }

    @Test void 受限JVM内存参数在审核投影和启动参数中一致() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        List<String> expected = List.of("-Xms32m", "-Xmx256m", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2");
        config(fixture, "jvm.config", String.join("\n", expected));
        var received = new AtomicReference<ChatRequest>();
        var outcome = reviewer(fixture, request -> { received.set(request); return ChatResponse.text(YES); })
                .review(request(fixture));

        assertTrue(outcome.approved(), outcome.reason());
        assertEquals(JSON.valueToTree(expected), projectedBuild(received.get()).path("jvmOptions"));
        assertEquals(expected, outcome.mavenBuild().arguments().subList(1, 1 + expected.size()));
        assertTrue(outcome.mavenBuild().arguments().indexOf(MavenBuildTestFixture.MAIN_CLASS) > expected.size());
        assertNoExecution(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-javaagent:outside.jar", "-Duser.home=outside", "--class-path outside",
            "-Xmx256m -XX:OnOutOfMemoryError=echo", "-Xmx256m -Djdk.module.path=outside"})
    void 项目JVM配置不能替换用户目录或加载代码(String value) throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        config(fixture, "jvm.config", value);

        refusedWithoutModel(fixture);
    }

    @Test void POM和mvn目录中的扩展都纳入审核并保留插件执行目标() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Files.writeString(fixture.workspace.resolve("pom.xml"), MavenBuildTestFixture.pom("extensions").replace("</project>", """
                <build>
                  <extensions><extension><groupId>fixture.ext</groupId><artifactId>pom-extension</artifactId><version>1</version></extension></extensions>
                  <plugins><plugin><groupId>fixture.plugin</groupId><artifactId>test-plugin</artifactId><version>2</version>
                    <executions><execution><phase>test</phase><goals><goal>check</goal></goals></execution></executions>
                  </plugin></plugins>
                </build></project>
                """));
        config(fixture, "extensions.xml", """
                <extensions>
                  <extension><groupId>fixture.ext</groupId><artifactId>core-extension</artifactId><version>3</version></extension>
                  <extension><groupId>fixture.ext</groupId><artifactId>pom-extension</artifactId><version>1</version></extension>
                </extensions>
                """);
        var received = new AtomicReference<ChatRequest>();
        var outcome = reviewer(fixture, request -> { received.set(request); return ChatResponse.text(YES); })
                .review(request(fixture));

        assertTrue(outcome.approved(), outcome.reason());
        JsonNode build = projectedBuild(received.get());
        assertEquals(Set.of("fixture.ext:pom-extension:1", "fixture.ext:core-extension:3"),
                StreamSupport.stream(build.path("extensions").spliterator(), false).map(JsonNode::asText).collect(Collectors.toSet()));
        assertEquals(2, build.path("extensions").size(), "同一扩展坐标不应重复投影");
        assertEquals(JSON.valueToTree(List.of("fixture.plugin:test-plugin:2 [test:check]")), build.path("plugins"));
        assertNoExecution(fixture);
    }

    @Test void 配置字节改变即使长度和修改时间相同也使旧批准失效() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Path configuration = config(fixture, "maven.config", "-Dtest=FirstTest\n");
        var timestamp = Files.getLastModifiedTime(configuration);
        long size = Files.size(configuration);
        var calls = new AtomicInteger();
        var review = reviewer(fixture, request -> { calls.incrementAndGet(); return ChatResponse.text(YES); });
        var first = review.review(request(fixture));
        assertTrue(first.approved(), first.reason());
        assertEquals(CommandReview.Status.CACHED_APPROVED, review.review(request(fixture)).status());

        Files.writeString(configuration, "-Dtest=OtherTest\n");
        Files.setLastModifiedTime(configuration, timestamp);
        assertEquals(size, Files.size(configuration));
        assertEquals(timestamp, Files.getLastModifiedTime(configuration));

        assertThrows(IOException.class, first.mavenBuild()::revalidate);
        var changed = review.review(request(fixture));
        assertEquals(CommandReview.Status.AI_APPROVED, changed.status(), changed.reason());
        assertEquals(2, calls.get());
        assertEquals(1, review.stats().cacheHits());
        assertNoExecution(fixture);
    }

    @Test void 审核后新增JVM配置不能沿用旧批准启动构建() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var outcome = reviewer(fixture, request -> ChatResponse.text(YES)).review(request(fixture));
        assertTrue(outcome.approved(), outcome.reason());
        config(fixture, "jvm.config", "-Xmx256m\n");
        var tools = WorkspaceTools.createWithCommandApproval(fixture.workspace, ignored -> outcome.decision());

        assertThrows(IOException.class, () -> tools.find("run_command").orElseThrow()
                .execute(JSON.writeValueAsString(Map.of("command", BUILD))));

        assertNoExecution(fixture);
    }

    @Test void 已批准后收到中断不能启动Maven且保留中断状态() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var outcome = reviewer(fixture, request -> ChatResponse.text(YES)).review(request(fixture));
        assertTrue(outcome.approved(), outcome.reason());
        var tools = WorkspaceTools.createWithCommandApproval(fixture.workspace, ignored -> {
            Thread.currentThread().interrupt();
            return outcome.decision();
        });
        try {
            assertThrows(InterruptedException.class, () -> tools.find("run_command").orElseThrow()
                    .execute(JSON.writeValueAsString(Map.of("command", BUILD))));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNoExecution(fixture);
    }

    @Test void 计划复核遇到中断立即停止且不清除取消标记() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        var plan = MavenBuildExecution.plan(request(fixture), fixture.runtime());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, plan::revalidate);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNoExecution(fixture);
    }

    @Test void 项目配置文件链接到外部时不能交审核模型放行() throws Exception {
        var fixture = MavenBuildTestFixture.create(temporary);
        Files.createDirectory(fixture.workspace.resolve(".mvn"));
        Path outside = Files.writeString(temporary.resolve("external-maven.config"), "-o\n");
        Path link = fixture.workspace.resolve(".mvn/maven.config");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "当前环境无法创建符号链接：" + unsupported.getClass().getSimpleName());
        }
        try {
            refusedWithoutModel(fixture);
            assertEquals("-o\n", Files.readString(outside));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    private static Path config(MavenBuildTestFixture fixture, String name, String value) throws IOException {
        Files.createDirectories(fixture.workspace.resolve(".mvn"));
        return Files.writeString(fixture.workspace.resolve(".mvn").resolve(name), value);
    }

    private static JsonNode projectedBuild(ChatRequest request) throws IOException {
        assertNotNull(request);
        assertTrue(request.tools().isEmpty());
        return JSON.readTree(request.messages().getLast().content()).path("build");
    }

    private static CommandReview.Outcome refusedWithoutModel(MavenBuildTestFixture fixture) {
        var calls = new AtomicInteger();
        var review = reviewer(fixture, request -> { calls.incrementAndGet(); return ChatResponse.text(YES); });
        var outcome = review.review(request(fixture));
        assertFalse(outcome.approved(), outcome.reason());
        assertNull(outcome.mavenBuild());
        assertEquals(0, calls.get(), "不满足本地边界的配置不能交给 AI 放行");
        assertEquals(0, review.stats().requests());
        assertNoExecution(fixture);
        return outcome;
    }

    private static void assertNoExecution(MavenBuildTestFixture fixture) {
        assertFalse(Files.exists(fixture.workspace.resolve(MavenBuildTestFixture.MARKER)));
    }

    private static CommandApproval.Request request(MavenBuildTestFixture fixture) {
        return new CommandApproval.Request(fixture.workspace, BUILD);
    }

    private static CommandReview reviewer(MavenBuildTestFixture fixture, ChatModel model) {
        var review = new CommandReview(model, Duration.ofSeconds(2), 8, ignored -> {}, ignored -> {},
                new ReadOnlyCommandGate(ignored -> fixture.runtime()));
        review.context(List.of(ChatMessage.user("请运行 Maven 测试验证当前修改")));
        return review;
    }
}
