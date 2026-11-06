package dev.codeharness.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WirePayloadsTest {
    @TempDir Path temp;

    @Test void repeatedPromptsAndToolsCanBeRestoredWithoutPlaceholderCollisions() throws Exception {
        var dictionary = new WirePayloads();
        var all = new HashMap<String, JsonNode>();
        var body = TraceRedactor.JSON.createObjectNode();
        body.put("model", "fixture");
        body.putArray("messages").addObject().put("role", "system").put("content", "唯一系统提示词" + "约束和说明".repeat(400));
        body.putArray("tools").addObject().put("type", "function").putObject("function").put("name", "read_file").put("description", "读取文件");
        body.put("literal", "[引用 wire-1]");
        body.put("escaped/~name", "另一段长文本".repeat(100));
        var first = dictionary.project(body);
        assertEquals(body, first.path("body"), "第一次应直接展示完整正文");
        assertTrue(first.path("references").isEmpty(), "首次内容不是重复项");
        assertEquals(body, restore(first, all));
        body.withArray("messages").addObject().put("role", "tool").put("content", "工具尾部不得截断".repeat(1000));
        var second = dictionary.project(body);
        assertEquals(body, restore(second, all));
        assertFalse(second.path("body").toString().contains("唯一系统提示词"));
        assertFalse(second.path("body").toString().contains("read_file"));
        assertTrue(second.path("body").toString().contains("工具尾部不得截断"));
        var retry = dictionary.project(body);
        assertTrue(retry.path("content_locations").isEmpty());
        assertEquals(body, restore(retry, all));
        assertEquals("[引用 wire-1]", restore(retry, all).path("literal").asText());
    }

    @Test void redactBeforeDedupAndPreserveUnknownProviderFields() throws Exception {
        var redactor = new TraceRedactor(List.of("fixture-secret-one", "fixture-secret-two"));
        String one = "{\"api_key\":\"fixture-secret-one\",\"messages\":[{\"content\":\"" + "长提示词".repeat(100) + " fixture-secret-one\"}],\"provider_extension\":{\"mode\":\"retain-me\"}}";
        String two = one.replace("fixture-secret-one", "fixture-secret-two");
        var dictionary = new WirePayloads();
        var all = new HashMap<String, JsonNode>();
        var first = dictionary.project(redactor.json(one));
        assertFalse(first.toString().contains("fixture-secret"));
        assertEquals("retain-me", restore(first, all).at("/provider_extension/mode").asText());
        var next = dictionary.project(redactor.json(two));
        assertTrue(next.path("content_locations").isEmpty());
        assertEquals(restore(first, all), restore(next, all));
    }

    @Test void wireLogIgnoresToolPreviewLimitAndIncludesErrorsAndReferences() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var warnings = new java.util.ArrayList<String>();
        var trace = ExecutionTrace.open(workspace, "wire-session", 128, List.of("fixture-credential"), warnings::add);
        String text = "工具大结果".repeat(1200) + "不可缺少的尾部";
        String request = TraceRedactor.JSON.writeValueAsString(Map.of("messages", List.of(Map.of("role", "tool", "content", text)), "model", "fixture-model"));
        trace.wireRequest(1, 1, "POST", "http://localhost/chat/completions", request);
        trace.wireResponse(1, 1, 400, "application/json", "{\"error\":{\"message\":\"fixture-credential failed\",\"request_id\":\"response-id\"}}", false, "HTTP 正文读取结束");
        trace.wireRequest(2, 1, "POST", "http://localhost/chat/completions", request);
        trace.wireResponse(2, 1, 200, "text/event-stream", "data: 部分内容\n\n", true, "响应读取未完成");
        trace.finish("FAILED", null); trace.close();
        String log = Files.readString(trace.path());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(log.contains("不可缺少的尾部"));
        assertEquals(1, log.split("不可缺少的尾部", -1).length - 1);
        assertTrue(log.contains("HTTP 400"));
        assertTrue(log.contains("response-id"));
        assertTrue(log.contains("未完成"));
        assertTrue(log.contains("报文已截断"));
        assertFalse(log.contains("fixture-credential"));
        assertTrue(log.contains("[引用 wire-1]"));
        assertTrue(log.contains("wire-1 → HTTP 请求报文 #1（尝试 1），body/messages/0/content"));
        assertFalse(log.contains("<a id=\"wire-"), "HTTP 报文不应出现空的 HTML 锚点");
    }

    @Test void oversizedBodyCanShrinkBelowCaptureLimitAfterRedaction() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("oversized"));
        var warnings = new java.util.ArrayList<String>();
        String secret = "test-credential-" + "z".repeat(1000);
        var trace = ExecutionTrace.open(workspace, "large-wire", 128, List.of(secret), warnings::add);
        trace.wireRequest(1, 1, "POST", "http://localhost/chat", secret.repeat(8300));
        trace.finish("COMPLETED", null); trace.close();
        assertTrue(warnings.isEmpty(), warnings.toString());
        String log = Files.readString(trace.path());
        assertTrue(log.contains("报文已截断"));
        assertTrue(log.contains("任务结束"));
        assertFalse(log.contains(secret));
    }

    @Test void repeatedContentWithinFirstRequestOnlyReplacesLaterOccurrences() throws Exception {
        var dictionary = new WirePayloads();
        var body = TraceRedactor.JSON.readTree("{\"messages\":[{\"content\":\"同一段正文\"},{\"content\":\"同一段正文\"}],\"empty\":\"\",\"absent\":null}");
        var projection = dictionary.project(body);
        assertEquals("同一段正文", projection.at("/body/messages/0/content").asText());
        assertEquals("[引用 wire-1]", projection.at("/body/messages/1/content").asText());
        assertEquals("/messages/0/content", projection.at("/content_locations/wire-1").asText());
        assertEquals(1, projection.path("references").size());
        assertEquals(body, restore(projection, new HashMap<>()));
    }

    @Test void textStreamAndEmptyContentRemainReadableBeforeTheyRepeat() throws Exception {
        var dictionary = new WirePayloads();
        var all = new HashMap<String, JsonNode>();
        var stream = TraceRedactor.JSON.getNodeFactory().textNode("data: " + "响应片段".repeat(50) + "\n\n");
        var first = dictionary.project(stream);
        assertEquals(stream, first.path("body"));
        assertEquals("", first.at("/content_locations/wire-1").asText());
        assertEquals(stream, restore(first, all));
        assertEquals(stream, restore(dictionary.project(stream), all));
        var empty = TraceRedactor.JSON.readTree("{\"content\":\"\"}");
        assertEquals(empty, dictionary.project(empty).path("body"));
    }

    @Test void referenceLocationsCannotInjectMarkdownOrHtml() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("escaped-location"));
        var warnings = new java.util.ArrayList<String>();
        var trace = ExecutionTrace.open(workspace, "escaped-wire", 128, List.of(), warnings::add);
        String request = TraceRedactor.JSON.writeValueAsString(Map.of("field\n## 冒充标题<script>", "长内容".repeat(100)));
        trace.wireRequest(1, 1, "POST", "http://localhost/chat", request);
        trace.wireRequest(2, 1, "POST", "http://localhost/chat", request);
        trace.close();
        String log = Files.readString(trace.path());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertFalse(log.contains("\n## 冒充标题"));
        assertTrue(log.contains("&lt;script&gt;"));
    }

    static JsonNode restore(ObjectNode projection, Map<String, JsonNode> dictionary) {
        projection.path("content_locations").fields().forEachRemaining(entry ->
                dictionary.put(entry.getKey(), projection.path("body").at(entry.getValue().asText())));
        JsonNode result = projection.path("body").deepCopy();
        var references = projection.path("references").fields();
        while (references.hasNext()) {
            var entry = references.next();
            JsonNode original = dictionary.get(entry.getValue().asText());
            assertNotNull(original, "引用必须能从当前或先前事件定位");
            String pointer = entry.getKey();
            if (pointer.isEmpty()) { result = original.deepCopy(); continue; }
            int slash = pointer.lastIndexOf('/');
            JsonNode parent = result.at(pointer.substring(0, slash));
            String key = pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
            if (parent.isObject()) ((ObjectNode) parent).set(key, original.deepCopy());
            else ((com.fasterxml.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(key), original.deepCopy());
        }
        return result;
    }
}
