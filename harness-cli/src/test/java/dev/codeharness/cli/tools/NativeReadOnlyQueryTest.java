package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeReadOnlyQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIND = "find . -name AGENTS.md -not -path './.git/*' -not -path './.harness/*'";
    @TempDir Path temporary;

    private Path workspace() throws IOException { return Files.createDirectories(temporary.resolve("workspace")).toRealPath(); }
    private NativeReadOnlyQuery.Plan plan(Path workspace, String command) throws IOException {
        return NativeReadOnlyQuery.plan(new CommandApproval.Request(workspace, command));
    }
    private String run(Path root, String command) throws Exception { return NativeReadOnlyQuery.execute(plan(root, command)); }

    @Test void acceptsOnlyKnownQueriesWithoutInterpretingShellSyntax() {
        for (String command : List.of("pwd", "cd", "ls", "ls -l", "ls -a", "ls -la", "ls -al",
                "dir", "dir /b", "dir /a", "dir /a /b", "dir /b /a", FIND,
                "find . -name AGENTS.md -not -path './.git/*'", "rg --files",
                "rg --files --hidden -g AGENTS.md -g '!.git'", "rg --files --glob \"AGENTS.md\"",
                "rg --files -g AGENTS.md -g README.md -g '*.java' -g pom.xml -g '*test*' -g '*Test*' -g '.github/**'",
                "rg --files -g *", "rg --files -g '*.env'", "rg --files -g '**/*'"))
            assertTrue(NativeReadOnlyQuery.supports(command), command);
        for (String command : List.of("ls; rm -rf .", "ls && echo hi", "dir & del file", "pwd > out",
                "rg --files | cat", "rg --files ..", "rg --files --follow", "rg --files -g '*.java' --no-ignore",
                "rg --files -g", "rg --hidden", "ls ..", "cd ..", "ls\n", " ls", "ls ",
                FIND + " -exec echo {} ;", "find . -name *.java", "rg --files -g $(whoami)"))
            assertFalse(NativeReadOnlyQuery.supports(command), command);
        assertFalse(NativeReadOnlyQuery.supports(null));
    }

    @Test void returnsCanonicalWorkingDirectoryWithoutLaunchingAProgram() throws Exception {
        Path root = workspace();
        assertEquals(root + "\n", run(root, "pwd"));
        assertEquals(root + "\n", run(root, "cd"));
        assertThrows(IOException.class, () -> plan(root, "pwd && echo unexpected"));
    }

    @Test void 多筛选保留长度与数量边界且不会被扩大为任意shell() {
        String repeated = "rg --files" + " -g AGENTS.md".repeat(64);
        assertTrue(repeated.length() > 256);
        assertTrue(NativeReadOnlyQuery.supports(repeated));
        assertFalse(NativeReadOnlyQuery.supports(repeated + " -g pom.xml"));
        String atLimit = "rg" + " ".repeat(4096 - "rg --files".length() + 1) + "--files";
        assertEquals(4096, atLimit.length());
        assertTrue(NativeReadOnlyQuery.supports(atLimit));
        assertFalse(NativeReadOnlyQuery.supports(atLimit.replace("rg ", "rg  ")));
        for (String suffix : List.of(" | cat", " & echo x", " -g $(whoami)", " -g '!../outside'", "\r", "\t"))
            assertFalse(NativeReadOnlyQuery.supports(repeated + suffix));
    }

    @Test void listsNamesAndLongMetadataWithConsistentHiddenHandling() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("visible.txt"), "abc");
        Files.writeString(root.resolve(".hidden"), "secret");
        Files.createDirectory(root.resolve("folder"));
        assertEquals("folder/\nvisible.txt\n", run(root, "ls"));
        assertTrue(run(root, "ls -a").contains(".hidden\n"));
        assertTrue(run(root, "dir /a").contains(".hidden\n"));
        String detail = run(root, "ls -la");
        assertTrue(detail.contains("文件\t3 字节\tvisible.txt"), detail);
        assertTrue(detail.contains("目录\t—\tfolder/"), detail);
    }

    @Test void exactFindWorksWithoutExternalRipgrepAndExcludesInternalDirectories() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("AGENTS.md"), "root");
        Files.createDirectories(root.resolve("src/nested"));
        Files.writeString(root.resolve("src/nested/AGENTS.md"), "nested");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/AGENTS.md"), "private");
        Files.createDirectories(root.resolve(".harness"));
        Files.writeString(root.resolve(".harness/AGENTS.md"), "private");
        Files.writeString(root.resolve("rg.cmd"), "@echo external-tool-was-called>unexpected.txt");
        assertEquals("AGENTS.md\nsrc/nested/AGENTS.md\n", run(root, FIND));
        assertEquals(run(root, FIND), run(root, "find . -name AGENTS.md -not -path './.git/*'"));
        assertFalse(Files.exists(root.resolve("unexpected.txt")));
    }

    @Test void ripgrepFormsAreBoundedNativeNamesAndDiscloseIgnoreDifference() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("visible.txt"), "content");
        Files.writeString(root.resolve("ignored.txt"), "content");
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\n");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/AGENTS.md"), "content");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/private"), "content");
        String visible = run(root, "rg --files");
        assertTrue(visible.contains("不读取 .gitignore/.ignore"), visible);
        assertTrue(visible.contains("ignored.txt\n"), visible);
        assertFalse(visible.contains(".hidden/AGENTS.md"), visible);
        assertFalse(visible.contains(".git/private"), visible);
        String agents = run(root, "rg --files --hidden -g AGENTS.md -g '!.git'");
        assertTrue(agents.contains(".hidden/AGENTS.md\n"), agents);
        assertFalse(agents.contains("visible.txt"), agents);
    }

    @Test void reviewedSourceFileGlobsListOnlyMatchingNamesIncludingExplicitGithubTree() throws Exception {
        Path root = workspace();
        for (String name : List.of("AGENTS.md", "README.md", "nested/README.md", "pom.xml",
                "src/Main.java", "src/NativeReadOnlyQueryTest.java", "docs/test-guide.md", "docs/Testing.md",
                ".github/workflows/build.yml", ".github/private.txt", ".other/README.md",
                ".git/README.md", ".harness/pom.xml")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "private-file-content-must-not-be-read");
        }
        String command = "rg --files -g AGENTS.md -g README.md -g '*.java' -g pom.xml -g '*test*' -g '*Test*' -g '.github/**'";
        String result = run(root, command);
        for (String expected : List.of("AGENTS.md", "README.md", "nested/README.md", "pom.xml", "src/Main.java",
                "src/NativeReadOnlyQueryTest.java", "docs/test-guide.md", "docs/Testing.md", ".github/workflows/build.yml"))
            assertTrue(result.contains("\n" + expected + "\n"), expected + "\n" + result);
        assertTrue(result.contains("\n.github/private.txt\n"), result);
        assertFalse(result.contains(".other/README.md"), result);
        assertFalse(result.contains("\n.git/README.md\n"), result);
        assertFalse(result.contains("\n.harness/pom.xml\n"), result);
        assertFalse(result.contains("private-file-content-must-not-be-read"), result);
    }

    @Test void rejectsReplacedWorkspaceIdentity() throws Exception {
        Path root = workspace();
        NativeReadOnlyQuery.Plan plan = plan(root, "ls");
        Files.move(root, temporary.resolve("previous"));
        Files.createDirectory(root);
        Files.writeString(root.resolve("not-approved.txt"), "new");
        IOException error = assertThrows(IOException.class, () -> NativeReadOnlyQuery.execute(plan));
        assertTrue(error.getMessage().contains("身份已变化"), error.getMessage());
    }

    @Test void excludesSymlinksEvenWhenTheirTargetsAreInsideWorkspace() throws Exception {
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("AGENTS.md"), "outside");
        Files.writeString(root.resolve("visible.txt"), "inside");
        try {
            Files.createSymbolicLink(root.resolve("external"), outside);
            Files.createSymbolicLink(root.resolve("internal.txt"), root.resolve("visible.txt"));
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            assumeTrue(false, "当前平台不允许创建符号链接：" + error.getMessage());
        }
        assertEquals("visible.txt\n", run(root, "ls -a"));
        assertEquals("", run(root, FIND));
        assertThrows(IOException.class, () -> plan(root.resolve("external"), "ls"));
    }

    @Test void excludesWindowsJunctionsAndRejectsJunctionWorkspace() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "Windows junction 测试");
        Path root = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("AGENTS.md"), "outside");
        Path junction = root.resolve("junction");
        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String diagnostic = new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), diagnostic);
        try {
            assertEquals("", run(root, FIND));
            assertEquals("", run(root, "ls -a"));
            assertThrows(IOException.class, () -> plan(junction, "ls"));
        } finally { Files.deleteIfExists(junction); }
    }

    @Test void stopsAtVisitResultOutputAndDepthLimits() throws Exception {
        Path root = workspace();
        for (int index = 0; index < 20; index++) Files.writeString(root.resolve("file-" + index + "x".repeat(70)), "");
        for (NativeReadOnlyQuery.Limits limits : List.of(
                new NativeReadOnlyQuery.Limits(3, 100, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 3, 1000, 10, Duration.ofSeconds(2)),
                new NativeReadOnlyQuery.Limits(100, 100, 200, 10, Duration.ofSeconds(2)))) {
            String result = NativeReadOnlyQuery.execute(plan(root, "ls"), limits);
            assertTrue(result.contains("结果已截断"), result);
            assertTrue(result.length() <= limits.outputChars(), result);
        }
        Files.createDirectories(root.resolve("nested/deeper"));
        Files.writeString(root.resolve("nested/deeper/AGENTS.md"), "");
        String deep = NativeReadOnlyQuery.execute(plan(root, FIND),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 1, Duration.ofSeconds(2)));
        assertTrue(deep.contains("结果已截断"), deep);
        assertFalse(deep.contains("nested/deeper/AGENTS.md"), deep);
    }

    @Test void checksTimeoutAndPreservesInterruption() throws Exception {
        Path root = workspace();
        String timeout = NativeReadOnlyQuery.execute(plan(root, "ls"),
                new NativeReadOnlyQuery.Limits(100, 100, 1000, 10, Duration.ofNanos(1)));
        assertTrue(timeout.contains("结果已截断"), timeout);
        NativeReadOnlyQuery.Plan plan = plan(root, FIND);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> NativeReadOnlyQuery.execute(plan));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void dispatchesNativeDecisionAndRejectsMismatchedOriginalRequest() throws Exception {
        Path root = workspace();
        Files.writeString(root.resolve("AGENTS.md"), "hello");
        NativeReadOnlyQuery.Plan plan = plan(root, FIND);
        var tools = WorkspaceTools.createWithCommandApproval(root, request -> CommandApproval.Decision.nativeQuery(plan));
        assertEquals("退出码：0\nAGENTS.md\n", tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", FIND))));
        assertThrows(SecurityException.class, () -> tools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", "echo unsafe > unexpected.txt"))));
        assertFalse(Files.exists(root.resolve("unexpected.txt")));
        Path other = Files.createDirectory(temporary.resolve("other")).toRealPath();
        var otherTools = WorkspaceTools.createWithCommandApproval(other, request -> CommandApproval.Decision.nativeQuery(plan));
        assertThrows(SecurityException.class, () -> otherTools.find("run_command").orElseThrow().execute(JSON.writeValueAsString(Map.of("command", FIND))));
    }

    @Test void nativeDecisionRejectsMixedOrDeniedPlans() throws Exception {
        NativeReadOnlyQuery.Plan plan = plan(workspace(), "ls");
        assertThrows(IllegalArgumentException.class, () -> new CommandApproval.Decision(false, List.of(), plan));
        assertThrows(IllegalArgumentException.class, () -> new CommandApproval.Decision(true, List.of("ls"), plan));
        assertNull(CommandApproval.Decision.shell().nativeQuery());
        assertNull(new CommandApproval.Decision(true, List.of("ls")).nativeQuery());
        assertEquals(plan, CommandApproval.Decision.nativeQuery(plan).nativeQuery());
    }

    @Test void dispatchRevalidatesWorkspaceAfterApprovalAndHonorsCancellation() throws Exception {
        Path root = workspace();
        NativeReadOnlyQuery.Plan plan = plan(root, "ls");
        var replaced = WorkspaceTools.createWithCommandApproval(root, request -> {
            try {
                Files.move(root, temporary.resolve("previous"));
                Files.createDirectory(root);
                return CommandApproval.Decision.nativeQuery(plan);
            } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        String input = JSON.writeValueAsString(Map.of("command", "ls"));
        assertThrows(IOException.class, () -> replaced.find("run_command").orElseThrow().execute(input));
        NativeReadOnlyQuery.Plan newPlan = plan(root, "ls");
        var cancelled = WorkspaceTools.createWithCommandApproval(root, request -> {
            Thread.currentThread().interrupt();
            return CommandApproval.Decision.nativeQuery(newPlan);
        });
        try {
            assertThrows(InterruptedException.class, () -> cancelled.find("run_command").orElseThrow().execute(input));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
