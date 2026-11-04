package dev.codeharness.cli.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.cli.tools.CommandApproval;
import dev.codeharness.cli.tools.WorkspaceTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RipgrepReviewTest {
    private static final String REPORTED_QUERY = "rg --files -g AGENTS.md -g pom.xml -g '*Test*' -g '*Application.java' -g '*Controller.java' -g '*ServiceImpl.java' -g '*Security*' -g '*ExceptionHandler*' -g 'application*.yml' -g 'application*.yaml' -g '.github/**' -g '!target'";
    @TempDir Path root;

    @Test void 完整Java筛选及构建目录排除直接本地放行() throws Exception {
        List<String> included = List.of("AGENTS.md", "pom.xml", "src/AppTest.java", "src/DemoApplication.java",
                "src/UserController.java", "src/UserServiceImpl.java", "src/SecurityConfig.java", "src/GlobalExceptionHandler.java",
                "config/application-dev.yml", "config/application.yaml", ".github/workflows/build.yml", "nested/AGENTS.md");
        List<String> excluded = List.of("target/AppTest.java", "nested/target/pom.xml", ".github/target/build.yml",
                ".git/AGENTS.md", ".harness/pom.xml", "src/Other.java", "application.yaml.bak", "AGENTS.mdAGENTS.md");
        for (String name : java.util.stream.Stream.concat(included.stream(), excluded.stream()).toList()) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "不应读取的文件内容");
        }
        var reviewer = new CommandReview(sent -> fail("纯文件名查询不调用审核模型"), Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            var outcome = reviewer.review(request);
            assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status(), outcome.reason());
            assertNotNull(outcome.nativeQuery());
            assertTrue(outcome.arguments().isEmpty());
            return outcome.decision();
        });
        // 包含超过旧 256 字符限制的等价 --glob 形式。
        for (String query : List.of(REPORTED_QUERY, REPORTED_QUERY.replace(" -g ", " --glob "))) {
            String output = tools.find("run_command").orElseThrow().execute(new ObjectMapper().writeValueAsString(Map.of("command", query)));
            List<String> names = output.lines().toList();
            for (String name : included) assertTrue(names.contains(name), name + "\n" + output);
            for (String name : excluded) assertFalse(names.contains(name), name + "\n" + output);
            assertFalse(output.contains("不应读取的文件内容"));
        }
        assertEquals(2, reviewer.stats().localApprovals());
        assertEquals(0, reviewer.stats().requests());
        assertEquals(0, reviewer.stats().manual());
    }

    @Test void 长查询仍拒绝追加命令和控制字符() throws Exception {
        var reviewer = new CommandReview(sent -> fail("无受限计划不得交给AI放行"), Duration.ofSeconds(2), 8, ignored -> {});
        for (String query : List.of(REPORTED_QUERY + " && echo bad", REPORTED_QUERY + " > out.txt",
                REPORTED_QUERY + " --follow", REPORTED_QUERY + " ../outside", REPORTED_QUERY + "\n",
                REPORTED_QUERY + " --pre malicious", "rg --files" + " -g AGENTS.md".repeat(65))) {
            var result = reviewer.review(new CommandApproval.Request(root, query));
            assertFalse(result.approved(), query);
            assertNull(result.nativeQuery());
        }
        String unknownGlob = reviewer.review(new CommandApproval.Request(root, REPORTED_QUERY + " --pre malicious")).reason();
        assertFalse(unknownGlob.contains("过长"), unknownGlob);
        assertFalse(unknownGlob.contains("控制字符"), unknownGlob);
        assertEquals(0, reviewer.stats().requests());
    }

    @Test void 原生指引文件发现无需外部程序或模型且排除内部目录() throws Exception {
        for (String name : List.of("rg.exe", "rg.cmd", "rg")) Files.writeString(root.resolve(name), "must not execute");
        for (String name : List.of("AGENTS.md", "nested/AGENTS.md", ".hidden/AGENTS.md", ".git/AGENTS.md", ".harness/AGENTS.md")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "private-instruction-contents");
        }
        var reviewer = new CommandReview(sent -> fail("原生指引文件发现不得调用模型"),
                Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, pending -> {
            var result = reviewer.review(pending);
            assertTrue(result.approved(), result.reason());
            assertEquals(CommandReview.Status.LOCAL_APPROVED, result.status());
            assertNotNull(result.nativeQuery(), "原生查询不依赖 PATH 中的 rg");
            assertTrue(result.arguments().isEmpty(), "不得提供任何外部可执行参数");
            return result.decision();
        });
        List<String> queries = List.of("rg --files --hidden -g AGENTS.md -g '!.git'",
                "rg --files --hidden -g AGENTS.md -g '!.git' -g '!.harness'",
                "find . -name AGENTS.md -not -path './.git/*'",
                "find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'");
        for (String query : queries) {
            String output = tools.find("run_command").orElseThrow().execute(
                    new ObjectMapper().writeValueAsString(Map.of("command", query)));
            assertTrue(output.startsWith("退出码：0"), output);
            List<String> names = output.lines().filter(line -> line.endsWith("AGENTS.md")).toList();
            assertEquals(List.of(".hidden/AGENTS.md", "AGENTS.md", "nested/AGENTS.md"), names, output);
            assertFalse(output.contains(".git/AGENTS.md"), output);
            assertFalse(output.contains(".harness/AGENTS.md"), output);
            assertFalse(output.contains("private-instruction-contents"), output);
            assertFalse(output.contains("must not execute"), output);
        }
        assertEquals(0, reviewer.stats().requests());
        assertEquals(queries.size(), reviewer.stats().localApprovals());
    }

    @Test void 本地目录和工作区查询通过原生计划直接执行() throws Exception {
        Files.writeString(root.resolve("visible.txt"), "file-content-not-for-query");
        var reviewer = new CommandReview(sent -> fail("目录和工作区查询不得调用模型"),
                Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, pending -> {
            var result = reviewer.review(pending);
            assertEquals(CommandReview.Status.LOCAL_APPROVED, result.status());
            assertTrue(result.approved());
            assertNotNull(result.nativeQuery());
            assertTrue(result.arguments().isEmpty());
            return result.decision();
        });
        List<String> queries = List.of("ls", "ls -la", "dir", "dir /a", "pwd", "cd", "rg --files");
        for (String query : queries) {
            String output = tools.find("run_command").orElseThrow().execute(
                    new ObjectMapper().writeValueAsString(Map.of("command", query)));
            assertTrue(output.startsWith("退出码：0"), output);
            assertTrue(output.contains(query.equals("pwd") || query.equals("cd")
                    ? root.toRealPath().toString() : "visible.txt"), output);
            assertFalse(output.contains("file-content-not-for-query"), output);
        }
        assertEquals(0, reviewer.stats().requests());
        assertEquals(queries.size(), reviewer.stats().localApprovals());
    }

    @Test void 受限多文件名筛选由本地审核器放行且不执行rg() throws Exception {
        for (String name : List.of("AGENTS.md", "README.md", "nested/README.md", "pom.xml", "src/Main.java",
                "src/NativeReadOnlyQueryTest.java", "docs/test-guide.md", "docs/Testing.md",
                ".github/workflows/build.yml", ".git/README.md", ".harness/pom.xml")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "file contents must never be read");
        }
        var reviewer = new CommandReview(sent -> fail("文件名清单不得调用审核模型"),
                Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> {
            var result = reviewer.review(request);
            assertTrue(result.approved(), result.reason());
            assertEquals(CommandReview.Status.LOCAL_APPROVED, result.status());
            assertNotNull(result.nativeQuery());
            assertTrue(result.arguments().isEmpty());
            return result.decision();
        });
        String query = "rg --files -g AGENTS.md -g pom.xml -g '*Test*' -g 'application*' -g '*Security*' -g '*Controller.java' -g '*ServiceImpl.java'";
        String output = tools.find("run_command").orElseThrow().execute(
                new ObjectMapper().writeValueAsString(Map.of("command", query)));
        for (String expected : List.of("AGENTS.md", "pom.xml", "src/NativeReadOnlyQueryTest.java", "docs/Testing.md"))
            assertTrue(output.contains("\n" + expected + "\n"), expected + "\n" + output);
        assertFalse(output.contains(".git/README.md"), output);
        assertFalse(output.contains(".harness/pom.xml"), output);
        assertFalse(output.contains("file contents must never be read"), output);
        assertEquals(0, reviewer.stats().requests());
        assertEquals(1, reviewer.stats().localApprovals());
        // 通用 glob 只查询名称；不再根据文件后缀枚举完整命令白名单。
        Files.writeString(root.resolve("fixture.secrets"), "正文不能出现在文件名查询中");
        var generic = reviewer.review(new CommandApproval.Request(root, "rg --files -g '*.secrets'"));
        assertTrue(generic.approved());
        assertEquals(CommandReview.Status.LOCAL_APPROVED, generic.status());
        String names = dev.codeharness.cli.tools.NativeReadOnlyQuery.execute(generic.nativeQuery());
        assertTrue(names.contains("fixture.secrets"));
        assertFalse(names.contains("正文不能出现在文件名查询中"));
        assertEquals(0, reviewer.stats().requests());
    }

    @Test void 查询附加命令和越界参数不能进入原生快速路径() throws Exception {
        var reviewer = new CommandReview(sent -> fail("不支持的查询不得交给模型放行"),
                Duration.ofSeconds(2), 8, ignored -> {});
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> reviewer.review(request).decision());
        for (String query : List.of("ls -la && echo unsafe > output.txt", "pwd > output.txt",
                "rg --files --hidden -g AGENTS.md -g '!.git' && echo unsafe", "rg --files ../outside",
                "find . -name AGENTS.md -not -path './.git/*' -exec echo unsafe ;")) {
            var result = reviewer.review(new CommandApproval.Request(root.toRealPath(), query));
            assertFalse(result.approved(), query);
            assertNull(result.nativeQuery(), query);
            assertThrows(SecurityException.class, () -> tools.find("run_command").orElseThrow().execute(
                    new ObjectMapper().writeValueAsString(Map.of("command", query))), query);
        }
        assertEquals(0, reviewer.stats().requests());
        assertFalse(Files.exists(root.resolve("output.txt")));
    }

    @Test void 用户报告的Java应用查询只列出匹配文件且不需要AI或人工() throws Exception {
        var files = List.of("AGENTS.md", "pom.xml", "src/AppTest.java", "config/application-dev.yml",
                "src/SecurityConfig.java", "src/HealthController.java", "src/UserServiceImpl.java");
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "sensitive contents must not be read");
        }
        Files.writeString(root.resolve("unmatched.txt"), "do not read");
        var reviewer = new CommandReview(sent -> fail("原生文件名查询不得调用审核模型"),
                Duration.ofSeconds(2), 8, ignored -> {});
        String command = "rg --files -g AGENTS.md -g pom.xml -g '*Test*' -g 'application*' -g '*Security*' -g '*Controller.java' -g '*ServiceImpl.java'";
        var outcome = reviewer.review(new CommandApproval.Request(root, command));
        assertEquals(CommandReview.Status.LOCAL_APPROVED, outcome.status());
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> reviewer.review(request).decision());
        String output = tools.find("run_command").orElseThrow().execute(new ObjectMapper().writeValueAsString(Map.of("command", command)));
        for (String file : files) assertTrue(output.contains("\n" + file + "\n"), output);
        assertFalse(output.contains("unmatched.txt"));
        assertFalse(output.contains("sensitive contents"));
        assertEquals(0, reviewer.stats().requests());
    }
}
