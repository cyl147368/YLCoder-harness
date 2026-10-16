package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 加载显式选择的配置或用户配置；仓库配置需要显式信任。 */
public final class ConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {}

    public static HarnessConfig load(Path workingDirectory, Path explicitFile) throws IOException {
        return load(workingDirectory, explicitFile, false);
    }

    public static HarnessConfig load(Path workingDirectory, Path explicitFile, boolean trustProjectConfig) throws IOException {
        Path selected = explicitFile;
        if (selected == null) {
            Path project = workingDirectory.resolve(".harness/config.yaml");
            Path user = Path.of(System.getProperty("user.home"), ".code-harness/config.yaml");
            if (trustProjectConfig && Files.isRegularFile(project)) selected = project;
            else if (Files.isRegularFile(user)) selected = user;
        }
        if (selected == null) return new HarnessConfig();
        if (!Files.exists(selected)) throw new IOException("配置文件不存在：" + selected);
        if (!Files.isRegularFile(selected)) throw new IOException("配置路径不是文件：" + selected);
        HarnessConfig config;
        try { config = YAML.readValue(selected.toFile(), HarnessConfig.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // 解析器异常可能引用任意配置值，包括意外写入的秘密。
            throw new IOException("YAML 配置无效或包含不支持的字段；请参阅 config.example.yaml");
        }
        if (config == null) throw new IOException("配置文件为空：" + selected);
        if (config.agent == null || config.permissions == null || config.headerEnv == null || config.mcp == null) {
            throw new IOException("agent、permissions、header-env 和 mcp 不能为 null");
        }
        if (config.timeoutSeconds < 1) throw new IOException("timeout-seconds 必须为正数");
        if (config.permissions.reviewTimeoutSeconds < 1 || config.permissions.reviewTimeoutSeconds > 30
                || config.permissions.maxAutoReviews < 1 || config.permissions.maxAutoReviews > 32) {
            throw new IOException("permissions.review-timeout-seconds 必须为 1..30，max-auto-reviews 必须为 1..32");
        }
        if (!TokenizerInputTokenCounter.supported(config.agent.tokenCounter)) {
            throw new IOException("agent.token-counter 必须是 utf16、cl100k 或 o200k");
        }
        if (config.agent.parallelReads < 1 || config.agent.parallelReads > 8) {
            throw new IOException("agent.parallel-reads 必须在 1 到 8 之间");
        }
        if (config.provider == null || config.provider.isBlank()
                || config.model == null || config.model.isBlank()) throw new IOException("provider 和 model 不能为空白");
        if (config.agent.maxIterations < 1 || config.agent.contextBudgetTokens < 64
                || config.agent.maxToolOutputChars < 128 || (config.agent.maxOutputTokens != null && config.agent.maxOutputTokens < 1)) {
            throw new IOException("智能体限制无效：迭代次数和输出上限须为正数，context >= 64，工具输出 >= 128");
        }
        validateMcp(config.mcp);
        return config;
    }

    private static void validateMcp(HarnessConfig.Mcp mcp) throws IOException {
        if (mcp.servers == null || mcp.servers.size() > 32 || mcp.maxMessageBytes < 1024
                || mcp.maxMessageBytes > 16_777_216 || mcp.maxSchemaBytes < 128
                || mcp.maxSchemaBytes > mcp.maxMessageBytes || mcp.maxTools < 1 || mcp.maxTools > 1024
                || mcp.maxPages < 1 || mcp.maxPages > 128) throw new IOException("MCP 限制无效");
        for (var entry : mcp.servers.entrySet()) {
            HarnessConfig.McpServer server = entry.getValue();
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_-]{1,48}") || server == null
                    || server.command == null || server.command.isBlank() || server.command.indexOf('\0') >= 0
                    || server.args == null || server.environmentEnv == null || server.timeoutSeconds < 1
                    || server.timeoutSeconds > 3600 || server.args.size() > 128
                    || server.args.stream().anyMatch(arg -> arg == null || arg.indexOf('\0') >= 0)) {
                throw new IOException("MCP 服务端配置无效");
            }
            for (var environment : server.environmentEnv.entrySet()) {
                if (environment.getKey() == null || environment.getKey().isBlank() || environment.getKey().contains("=")
                        || environment.getKey().indexOf('\0') >= 0 || environment.getValue() == null
                        || environment.getValue().isBlank()) throw new IOException("MCP 环境变量映射无效");
            }
        }
    }
}
