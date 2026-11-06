package dev.codeharness.cli.trace;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExecutionTraceTest {
    @TempDir Path temporary;
    private static final String SESSION = "79589132-7e6d-46eb-bfc5-6e1ab791f4b1";
    private Path workspace() throws IOException { return Files.createDirectory(temporary.resolve("workspace")); }
    private static ChatRequest request(String text) { return new ChatRequest(List.of(ChatMessage.user(text)), List.of(), 4096); }
    private static long send(ExecutionTrace trace, ChatRequest request) { return trace.request("agent", "custom", "test-model", true, 1, request); }
    private static String read(ExecutionTrace trace) throws IOException { assertNotNull(trace.path()); return Files.readString(trace.path()); }

    @Test void lazyOpenDisabledAndCloseNeverCreateFiles() throws Exception {
        Path workspace = workspace();
        ExecutionTrace trace = ExecutionTrace.open(workspace, SESSION, 2_000, List.of(), fail -> fail(fail));
        assertNull(trace.path());
        assertFalse(Files.exists(workspace.resolve(".harness")));
        trace.close(); trace.close();
        assertEquals(1, send(trace, request("ignored")));
        assertNull(trace.path());
        ExecutionTrace disabled = ExecutionTrace.disabled();
        send(disabled, request("ignored"));
        disabled.response(1, ChatResponse.text("ignored"), 1);
        disabled.failure(1, new IOException("ignored"), 1);
        disabled.finish("DONE", null); disabled.close();
        assertNull(disabled.path());
        assertFalse(Files.exists(workspace.resolve(".harness")));
    }

    @Test void recordsEveryActualAttemptRolesSchemaUsageAndTaskWithoutOpaqueMetadata() throws Exception {
        Path workspace = workspace();
        List<String> warnings = new ArrayList<>();
        ToolCall call = new ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}", Map.of("opaque", "HIDDEN-TOOL-METADATA"));
        ToolDefinition tool = new ToolDefinition("read_file", "读取文件说明唯一文本", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))));
        ChatRequest request = new ChatRequest(List.of(ChatMessage.system("系统提示唯一文本"), ChatMessage.user("用户提示唯一文本"),
                ChatMessage.assistantToolCalls("助手文本", List.of(call), Map.of("opaque", "HIDDEN-MESSAGE-METADATA")),
                ChatMessage.toolResult(call, "文件内容")), List.of(tool), 512);
        ExecutionTrace trace = ExecutionTrace.open(workspace, SESSION, 200, List.of(), warnings::add);
        long first = trace.request("agent", "custom", "my-model", true, 3, request);
        trace.failure(first, new IOException("响应提前结束"), 321);
        long second = trace.request("agent", "custom", "my-model", false, 3, request);
        trace.response(second, new ChatResponse("回复完成", List.of(), new TokenUsage(400, 50, 300), FinishReason.STOP,
                Map.of("opaque", "HIDDEN-RESPONSE-METADATA")), 98);
        trace.finish("COMPLETED", null); trace.close();
        String text = read(trace);
        assertEquals(1, first); assertEquals(2, second);
        assertTrue(text.contains("## 模型请求 #1")); assertTrue(text.contains("## 模型请求 #2"));
        assertTrue(text.contains("### 消息 1 · SYSTEM")); assertTrue(text.contains("### 消息 2 · USER"));
        assertTrue(text.contains("### 消息 3 · ASSISTANT")); assertTrue(text.contains("### 消息 4 · TOOL"));
        assertTrue(text.contains("厂商：custom")); assertTrue(text.contains("模型：my-model"));
        assertTrue(text.contains("maxOutputTokens：512"));
        assertTrue(text.contains("调用方式：流式")); assertTrue(text.contains("调用方式：非流式"));
        assertTrue(text.contains("inputSchema")); assertTrue(text.contains("读取文件说明唯一文本"));
        assertTrue(text.contains("工具调用 ID：call-1"));
        assertTrue(text.contains("输入 400；输出 50；缓存输入 300"));
        assertTrue(text.contains("耗时（毫秒）：321")); assertTrue(text.contains("结束原因：STOP"));
        assertTrue(text.contains("实际请求数：2")); assertTrue(text.contains("状态：COMPLETED"));
        assertFalse(text.contains("HIDDEN-"));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(workspace.resolve(".harness/logs/" + SESSION), trace.path().getParent());
        assertTrue(trace.path().getFileName().toString().matches("\\d{8}T\\d{6}\\.\\d{3}Z-[a-f0-9-]+\\.md"));
    }

    @Test void repeatedContentAndSchemaOnlyPrintedOnceWithResolvableReferences() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 200, List.of(), fail -> fail(fail));
        String prompt = "exact reusable prompt 你好";
        ToolDefinition tool = new ToolDefinition("read", "unique schema description", Map.of("type", "object"));
        ChatRequest request = new ChatRequest(List.of(ChatMessage.system(prompt), ChatMessage.user("task")), List.of(tool), null);
        send(trace, request); send(trace, request); trace.close();
        String text = read(trace);
        assertEquals(1, occurrences(text, prompt));
        assertEquals(1, occurrences(text, "unique schema description"));
        assertTrue(text.contains("原始 " + prompt.length() + " 字符；重复内容"));
        var references = Pattern.compile("\\[首次记录]\\(#(content-\\d+)\\)").matcher(text);
        int count = 0;
        while (references.find()) {
            count++;
            assertTrue(text.contains("<a id=\"" + references.group(1) + "\"></a>"));
            assertTrue(text.indexOf("<a id=\"" + references.group(1)) < references.start());
        }
        assertEquals(3, count);
    }

    @Test void preservesPromptWhitespaceAndStopsMarkdownAndControlInjection() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 200, List.of(), fail -> fail(fail));
        String prompt = "first\n  indented  \n" + Character.toString(96).repeat(5) + "\n## forged-heading\n<a id=\"forged\"></a>\u001b[31mred\u202e";
        send(trace, request(prompt)); trace.close();
        String text = read(trace);
        assertTrue(text.contains("    first\n      indented  \n"));
        assertTrue(text.contains("    ## forged-heading")); assertFalse(text.contains("\n## forged-heading"));
        assertTrue(text.contains("    <a id=\"forged\"></a>\\u001b[31mred\\u202e"));
        assertFalse(text.contains("\u001b")); assertFalse(text.contains("\u202e"));
    }

    @Test void truncatesToolResultsButKeepsNormalPromptAndReferencesFullContentHash() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 128, List.of(), fail -> fail(fail));
        ToolCall call = new ToolCall("id", "read_file", "{}");
        String prefix = "R".repeat(128);
        String result = prefix + "UNSHOWN-ONE";
        send(trace, request("P".repeat(300)));
        trace.toolStart(2, call); trace.toolResult(2, call, result, false);
        trace.toolResult(2, call, prefix + "UNSHOWN-TWO", true);
        trace.request("agent", "custom", "model", false, 3,
                new ChatRequest(List.of(ChatMessage.toolResult(call, result)), List.of(), null));
        trace.close(); String text = read(trace);
        assertTrue(text.contains("P".repeat(300)));
        assertFalse(text.contains("UNSHOWN"));
        assertEquals(2, occurrences(text, prefix));
        assertTrue(text.contains("已截断，仅展示前 128 字符"));
        assertTrue(text.contains("工具结果：原始 139 字符"));
        assertTrue(text.contains("## 工具开始")); assertTrue(text.contains("## 工具完成")); assertTrue(text.contains("## 工具失败"));
        assertTrue(text.contains("状态：成功")); assertTrue(text.contains("状态：失败"));
        assertTrue(text.contains("消息正文：原始 139 字符；重复内容"));
    }

    @Test void redactsKnownEscapedAndLateSecretsAndCredentialArgumentsBeforeDedup() throws Exception {
        String secret = "private-key-quote\"-slash\\-line\nend";
        String late = "later-private-review-key";
        String escaped = TraceRedactor.JSON.writeValueAsString(secret);
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 2_000, List.of(secret), fail -> fail(fail));
        send(trace, request("raw=" + secret + " encoded=" + escaped + "\nAuthorization: Bearer some-long-credential"));
        trace.addSecrets(List.of(late));
        ToolCall call = new ToolCall("call", "shell", "{\"password\":\"unknown-password\",\"nested\":{\"clientSecret\":\"unknown-client-secret\"},\"command\":\"curl --api-key unknown-cli-key\",\"safe\":\"keep\"}");
        trace.toolStart(1, call);
        trace.toolResult(1, call, "response-key=" + late, false);
        trace.failure(1, new IOException("api_key=unknown-param&value=ok url=https://user:pwd@example.invalid"), 1);
        trace.close(); String text = read(trace);
        for (String forbidden : List.of(secret, "private-key-quote", late, "some-long-credential", "unknown-password", "unknown-client-secret", "unknown-cli-key", "unknown-param", "user:pwd"))
            assertFalse(text.contains(forbidden), "不能泄漏 " + forbidden);
        assertTrue(text.contains("[已脱敏]")); assertTrue(text.contains("keep"));
    }

    @Test void recognizesCommonKeysAndJsonEscapingWithoutConfiguredSecret() {
        TraceRedactor redactor = new TraceRedactor(List.of());
        String common = "sk-proj-abcdefghijklmnop1234567890";
        String text = redactor.text("key=" + common + " OPENAI_API_KEY=plaintext abc --token flagsecret Authorization: Basic YWJjZGVmZ2g=");
        assertFalse(text.contains(common)); assertFalse(text.contains("plaintext")); assertFalse(text.contains("flagsecret"));
        assertFalse(text.contains("YWJjZGVmZ2g="));
        assertFalse(redactor.text("Cookie: session=unlisted-cookie; csrf=second-cookie").contains("cookie;"));
        assertFalse(redactor.text("Cookie: session=unlisted-cookie; csrf=second-cookie").contains("second-cookie"));
        assertFalse(redactor.text("-----BEGIN PRIVATE KEY-----\nprivate-key-material\n-----END PRIVATE KEY-----").contains("private-key-material"));
        String secret = "abc\"def\\ghi";
        redactor.add(List.of(secret));
        assertFalse(redactor.text("abc\\\"def\\\\ghi").contains("abc"));
    }

    @Test void longQuotedAndUnterminatedCredentialsAreRedactedWithoutRecursiveRegex() throws Exception {
        TraceRedactor redactor = new TraceRedactor(List.of());
        String value = "sensitive-value-".repeat(2_000);
        String encoded = TraceRedactor.JSON.writeValueAsString("segment\"\\tail".repeat(2_500));
        assertEquals("{\"token\":\"[已脱敏]\",\"safe\":\"kept\"}", assertDoesNotThrow(
                () -> redactor.text("{\"token\":\"" + value + "\",\"safe\":\"kept\"}")));
        assertEquals("{\"password\":\"[已脱敏]\",\"safe\":true}", assertDoesNotThrow(
                () -> redactor.text("{\"password\":" + encoded + ",\"safe\":true}")));
        assertEquals("curl --password \"[已脱敏]\" --output kept", assertDoesNotThrow(
                () -> redactor.text("curl --password \"" + value + "\" --output kept")));
        assertEquals("--api-key \"[已脱敏]\" next", assertDoesNotThrow(
                () -> redactor.text("--api-key '" + value + "' next")));
        assertEquals("password=\"[已脱敏]\"", assertDoesNotThrow(
                () -> redactor.text("password=\"" + value + " still-unclosed")));
        assertEquals("--token \"[已脱敏]\"", assertDoesNotThrow(
                () -> redactor.text("--token \"" + value + " still-unclosed")));
        String ordinary = "ordinary-content-".repeat(4_000);
        assertEquals(ordinary, assertDoesNotThrow(() -> redactor.text(ordinary)));
    }

    @Test void longCredentialToolOutputDoesNotInterruptTraceOrLeakBeforeTruncation() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 128, List.of(), warnings::add);
        String secret = "long-private-value-".repeat(2_000);
        String result = "{\"token\":\"" + secret + "\",\"safe\":\"kept\"}";
        ToolCall call = new ToolCall("long-result", "shell", "{}");
        assertDoesNotThrow(() -> {
            long id = send(trace, request("read task"));
            trace.toolStart(1, call);
            trace.toolResult(1, call, result, false);
            trace.response(id, ChatResponse.text("result recorded"), 100);
            trace.finish("COMPLETED", null);
        });
        trace.close();
        String log = read(trace);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(log.contains("## 工具完成"));
        assertTrue(log.contains("[已脱敏]"));
        assertTrue(log.contains("kept"));
        assertTrue(log.contains("result recorded"));
        assertTrue(log.contains("状态：COMPLETED"));
        assertFalse(log.contains("long-private-value"));
    }

    @Test void invalidToolArgumentsNeverFallBackToRawText() {
        TraceRedactor redactor = new TraceRedactor(List.of());
        String omitted = "[工具参数已省略：无法安全解析或脱敏]";
        assertEquals(omitted, redactor.arguments("{\"password\":\"private-unclosed"));
        assertEquals(omitted, redactor.arguments("{\"command\":\"safe\",\"command\":\"private-duplicate\"}"));
        assertEquals(omitted, redactor.arguments("{} private-trailing-content"));
        assertEquals(omitted, redactor.arguments("[".repeat(66) + "\"private-deep-content\"" + "]".repeat(66)));
    }

    @Test void structuredReviewShowsDecisionAndSanitizedReasonButNoExtraFieldsOrMetadata() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 2_000, List.of("secret-review-value"), fail -> fail(fail));
        long id = trace.request("reviewer", "review-provider", "review-model", false, 2, request("review-input"));
        trace.response(id, new ChatResponse("{\"decision\":\"approve\",\"reason\":\"只读，key=secret-review-value\"}", List.of(),
                new TokenUsage(20, 10, 5), FinishReason.STOP, Map.of("thinking", "PRIVATE-METADATA")), 10);
        long invalid = trace.request("reviewer", "p", "m", false, 3, request("review-input"));
        trace.partial(invalid, "PRIVATE-REASONING partial reviewer");
        trace.response(invalid, ChatResponse.text("{\"decision\":\"approve\",\"reason\":\"fine\",\"thinking\":\"PRIVATE-REASONING\"}"), 10);
        trace.close(); String text = read(trace);
        assertTrue(text.contains("来源：reviewer")); assertTrue(text.contains("审核结论：允许"));
        assertFalse(text.contains("\"decision\":\"approve\"")); assertTrue(text.contains("只读"));
        assertFalse(text.contains("secret-review-value")); assertFalse(text.contains("PRIVATE-"));
        assertTrue(text.contains("审核回复结构无效"));
    }

    @Test void partialFailureAndFinishPersistImmediatelyWithoutStackTrace() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 128, List.of("private-secret"), fail -> fail(fail));
        long id = send(trace, request("hi"));
        assertTrue(read(trace).contains("## 模型请求 #1"));
        trace.partial(id, "输出一半" + "private-secret" + "A".repeat(200));
        trace.failure(id, new IOException("断开 private-secret", new IOException("private-cause")), 300);
        trace.finish("FAILED", "断开 private-secret");
        String before = read(trace); trace.finish("COMPLETED", null); trace.close(); trace.close();
        assertEquals(before, read(trace));
        assertTrue(before.contains("未完成回复片段 #1")); assertTrue(before.contains("异常类型：IOException"));
        assertTrue(before.contains("耗时（毫秒）：300")); assertTrue(before.contains("状态：FAILED"));
        assertFalse(before.contains("private-secret")); assertFalse(before.contains("private-cause"));
        assertFalse(before.contains("at dev.codeharness"));
    }

    @Test void separateTasksNeverOverwriteExistingFileAndUsePrivatePermissions() throws Exception {
        Path workspace = workspace();
        ExecutionTrace first = ExecutionTrace.open(workspace, SESSION, 200, List.of(), fail -> fail(fail));
        send(first, request("first task")); first.close();
        String saved = read(first);
        ExecutionTrace second = ExecutionTrace.open(workspace, SESSION, 200, List.of(), fail -> fail(fail));
        send(second, request("second task")); second.close();
        assertNotEquals(first.path(), second.path()); assertEquals(saved, read(first));
        assertEquals("*", Files.readAllLines(workspace.resolve(".harness/logs/.gitignore")).getLast());
        if (Files.getFileStore(first.path()).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(first.path()));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(first.path().getParent()));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(first.path(), AclFileAttributeView.class);
            assertNotNull(acl);
            assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW)
                    .allMatch(entry -> entry.principal().equals(aclOwner(acl))));
        }
    }

    @Test void invalidWorkspaceIdsAndThrowingWarningAreFailSoft() throws Exception {
        Path workspace = workspace();
        for (String id : List.of("../outside", "a/b", "a:b", "")) {
            List<String> warnings = new ArrayList<>();
            ExecutionTrace trace = ExecutionTrace.open(workspace, id, 200, List.of(), warnings::add);
            assertDoesNotThrow(() -> send(trace, request("private message")));
            send(trace, request("second")); trace.finish("FAILED", null); trace.close();
            assertNull(trace.path()); assertEquals(1, warnings.size());
            assertFalse(warnings.getFirst().contains(workspace.toString())); assertFalse(warnings.getFirst().contains(id + "/"));
        }
        Path blocking = Files.writeString(workspace.resolve(".harness"), "do not replace");
        ExecutionTrace trace = ExecutionTrace.open(workspace, SESSION, 200, List.of(), warning -> { throw new IllegalStateException("private-warning-failure"); });
        assertDoesNotThrow(() -> send(trace, request("private message")));
        assertNull(trace.path()); assertEquals("do not replace", Files.readString(blocking));
    }

    @Test void refusesSymlinkLogsAndDoesNotTouchDestination() throws Exception {
        Path workspace = workspace();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createDirectory(workspace.resolve(".harness"));
        createSymbolicLinkOrSkip(workspace.resolve(".harness/logs"), outside);
        List<String> warnings = new ArrayList<>();
        ExecutionTrace trace = ExecutionTrace.open(workspace, SESSION, 200, List.of(), warnings::add);
        send(trace, request("not outside")); trace.close();
        assertNull(trace.path()); assertEquals(1, warnings.size());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    @Test void refusesHardlinkedIgnoreFileAndLiveLog() throws Exception {
        Path workspace = workspace();
        Path logs = Files.createDirectories(workspace.resolve(".harness/logs"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "must stay\n");
        createHardLinkOrSkip(logs.resolve(".gitignore"), outside);
        List<String> warnings = new ArrayList<>();
        ExecutionTrace refused = ExecutionTrace.open(workspace, SESSION, 200, List.of(), warnings::add);
        send(refused, request("must not leak")); refused.close();
        assertNull(refused.path()); assertEquals("must stay\n", Files.readString(outside)); assertEquals(1, warnings.size());
        Files.delete(logs.resolve(".gitignore"));
        ExecutionTrace trace = ExecutionTrace.open(workspace, SESSION, 200, List.of(), warnings::add);
        send(trace, request("before hardlink"));
        String saved = read(trace);
        createHardLinkOrSkip(temporary.resolve("copied-log.md"), trace.path());
        send(trace, request("after hardlink private content")); trace.close();
        assertEquals(saved, read(trace)); assertEquals(2, warnings.size());
    }

    @Test void detectsExternalAppendAndDoesNotOverwriteIt() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 200, List.of(), warnings::add);
        send(trace, request("first"));
        Files.writeString(trace.path(), "external-marker", StandardOpenOption.APPEND);
        String current = read(trace);
        send(trace, request("should not append")); trace.close();
        assertEquals(current, read(trace)); assertEquals(1, warnings.size());
    }

    @Test void explicitFileLimitStopsWithMarkerAndOnlyOneSafeWarning() throws Exception {
        var constructor = ExecutionTrace.class.getDeclaredConstructor(Path.class, String.class, int.class, Collection.class,
                Consumer.class, boolean.class, int.class);
        constructor.setAccessible(true);
        List<String> warnings = new ArrayList<>();
        ExecutionTrace trace = constructor.newInstance(workspace(), SESSION, 200, List.of(), (Consumer<String>) warnings::add, false, 16_384);
        send(trace, request("first"));
        send(trace, request("X".repeat(20_000)));
        send(trace, request("private-after-cap"));
        trace.finish("COMPLETED", null); trace.close();
        String text = read(trace);
        assertTrue(text.contains("## 日志停止记录")); assertFalse(text.contains("private-after-cap"));
        assertEquals(1, warnings.size()); assertTrue(warnings.getFirst().contains("上限"));
        assertTrue(Files.size(trace.path()) < 16_384);
    }

    @Test void requestIdsRemainUniqueWhenReviewAndAgentCallsOverlap() throws Exception {
        ExecutionTrace trace = ExecutionTrace.open(workspace(), SESSION, 200, List.of(), fail -> fail(fail));
        List<Long> ids = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> send(trace, request("parallel input"))));
            for (var task : tasks) ids.add(task.get());
        }
        trace.close();
        assertEquals(12, new HashSet<>(ids).size());
        String text = read(trace);
        for (long id : ids) assertTrue(text.contains("## 模型请求 #" + id + "\n"));
    }

    private static Object aclOwner(AclFileAttributeView acl) {
        try { return acl.getOwner(); } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static int occurrences(String text, String part) {
        int count = 0, start = 0;
        while ((start = text.indexOf(part, start)) >= 0) { count++; start += part.length(); }
        return count;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException denied) { assumeTrue(false, "当前环境不支持创建符号链接"); }
    }

    private static void createHardLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (IOException | UnsupportedOperationException denied) { assumeTrue(false, "当前环境不支持创建硬链接"); }
    }
}
