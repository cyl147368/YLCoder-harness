package dev.codeharness.cli.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.codeharness.cli.HarnessConfig;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import dev.codeharness.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 管理已配置的 stdio 服务端及其确定性命名空间工具注册。 */
public final class McpTools implements AutoCloseable {
    private final List<StdioMcpClient> clients = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();

    public static McpTools open(Path workspace, HarnessConfig.Mcp config, int maxOutputChars,
                                Predicate<String> approval) throws IOException, InterruptedException {
        McpTools result = new McpTools();
        try {
            for (var server : new java.util.TreeMap<>(config.servers).entrySet()) {
                StdioMcpClient client = new StdioMcpClient(workspace, server.getValue(), config.maxMessageBytes);
                result.clients.add(client);
                result.discover(server.getKey(), client, config, maxOutputChars, approval);
            }
            result.tools.sort(java.util.Comparator.comparing(tool -> tool.definition().name()));
            return result;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            result.close();
            throw failure;
        }
    }

    public ToolRegistry combine(ToolRegistry local) {
        List<Tool> combined = new ArrayList<>();
        for (var definition : local.definitions()) combined.add(local.find(definition.name()).orElseThrow());
        combined.addAll(tools);
        return new ToolRegistry(combined);
    }

    private void discover(String server, StdioMcpClient client, HarnessConfig.Mcp limits,
                          int maxOutputChars, Predicate<String> approval) throws IOException, InterruptedException {
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < limits.maxPages; page++) {
            JsonNode response = client.request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            JsonNode listed = response.path("tools");
            if (!listed.isArray()) throw new IOException("MCP tools/list 缺少 tools 数组");
            for (JsonNode tool : listed) {
                if (tools.size() >= limits.maxTools) throw new IOException("MCP 工具数量超过上限");
                String original = tool.path("name").asText();
                if (original.isBlank() || original.length() > 256 || original.chars().anyMatch(Character::isISOControl)
                        || !names.add(original)) {
                    throw new IOException("MCP 工具名称无效或重复");
                }
                JsonNode schema = tool.path("inputSchema");
                if (!schema.isObject() || !"object".equals(schema.path("type").asText())
                        || StdioMcpClient.JSON.writeValueAsBytes(schema).length > limits.maxSchemaBytes) {
                    throw new IOException("MCP 工具 schema 无效或超过上限");
                }
                Map<String, Object> inputSchema = StdioMcpClient.JSON.convertValue(schema, new TypeReference<>() {});
                String description = clip(tool.path("description").asText("外部 MCP 工具"), 2000);
                ToolDefinition definition = new ToolDefinition(toolName(server, original),
                        "MCP 服务端 " + server + ": " + description, inputSchema);
                tools.add(new Tool() {
                    @Override public ToolDefinition definition() { return definition; }
                    @Override public String execute(String argumentsJson) throws Exception {
                        if (!approval.test("MCP 工具 " + server + " / " + original)) {
                            throw new IOException("外部 MCP 工具权限被拒绝");
                        }
                        JsonNode arguments;
                        try { arguments = StdioMcpClient.JSON.readTree(argumentsJson); }
                        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                            throw new IOException("MCP 工具参数 JSON 无效");
                        }
                        if (arguments == null || !arguments.isObject()) throw new IOException("MCP 工具参数必须是 JSON 对象");
                        JsonNode result = client.request("tools/call", Map.of("name", original, "arguments", arguments));
                        String output = render(result, maxOutputChars);
                        if (result.path("isError").asBoolean(false)) throw new IOException("MCP 工具报告错误：" + output);
                        return output;
                    }
                });
            }
            JsonNode next = response.get("nextCursor");
            if (next == null) return;
            if (!next.isTextual() || next.asText().isBlank() || next.asText().length() > 2048 || !cursors.add(next.asText())) {
                throw new IOException("MCP tools/list 返回无效或重复的 cursor");
            }
            cursor = next.asText();
        }
        throw new IOException("MCP 工具分页超过上限");
    }

    static String toolName(String server, String original) {
        String readable = original.replaceAll("[^A-Za-z0-9_-]", "_");
        readable = readable.substring(0, Math.min(24, readable.length()));
        String namespace = server.substring(0, Math.min(16, server.length()));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((server + "\0" + original).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
            return "mcp_" + namespace + "_" + readable + "_" + hash;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String render(JsonNode result, int limit) throws IOException {
        if (!result.isObject()) throw new IOException("MCP tools/call 返回无效结果");
        StringBuilder output = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && !content.isArray()) throw new IOException("MCP 工具 content 必须是数组");
        if (content != null) for (JsonNode item : content) {
            String type = item.path("type").asText();
            String text = switch (type) {
                case "text" -> item.path("text").asText();
                case "resource" -> item.path("resource").path("text").asText("[已省略非文本 MCP 资源]");
                default -> "[已省略不支持的 MCP 内容类型：" + clip(type, 64) + "]";
            };
            if (!output.isEmpty()) output.append('\n');
            output.append(clip(text, limit));
            if (output.length() > limit) break;
        }
        if (result.has("structuredContent")) {
            if (!result.get("structuredContent").isObject()) throw new IOException("MCP structuredContent 必须是对象");
            output.append("\n结构化结果：").append(clip(result.get("structuredContent").toString(), limit));
        }
        return clip(output.isEmpty() ? "MCP 工具未返回文本。" : output.toString(), limit);
    }

    private static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        String marker = "\n… [已省略 MCP 输出] …\n";
        if (limit <= marker.length()) return text.substring(0, Math.max(0, limit));
        int remaining = limit - marker.length();
        int head = remaining / 2;
        return text.substring(0, head) + marker + text.substring(text.length() - (remaining - head));
    }

    @Override public void close() {
        for (int i = clients.size() - 1; i >= 0; i--) clients.get(i).close();
    }
}
