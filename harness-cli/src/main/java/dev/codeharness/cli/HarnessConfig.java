package dev.codeharness.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;

/** 面向用户的 YAML 配置。支持私有配置内密钥和环境变量引用，禁止整体记录配置。 */
public final class HarnessConfig {
    /** 仅为兼容旧 YAML 保留；CLI 工作区由启动目录或 --workspace 选择，此值不参与选择。 */
    public String workspace = ".";
    public String provider = "openai";
    public String model = "gpt-4.1-mini";
    @JsonProperty("base-url") public String baseUrl;
    @JsonProperty("api-key") public String apiKey;
    @JsonProperty("api-key-env") public String apiKeyEnv;
    @JsonProperty("timeout-seconds") public int timeoutSeconds = 120;
    @JsonProperty("header-env") public Map<String, String> headerEnv = Map.of();
    public Agent agent = new Agent();
    public Permissions permissions = new Permissions();
    public Mcp mcp = new Mcp();

    public static final class Agent {
        @JsonProperty("max-iterations") public int maxIterations = 30;
        @JsonProperty("parallel-reads") public int parallelReads = 1;
        @JsonProperty("context-budget-tokens") public int contextBudgetTokens = 48_000;
        @JsonProperty("token-counter") public String tokenCounter = "utf16";
        @JsonProperty("max-tool-output-chars") public int maxToolOutputChars = 12_000;
        @JsonProperty("max-output-tokens") public Integer maxOutputTokens = 3_000;
    }

    public static final class Permissions {
        @JsonProperty("allow-commands") public boolean allowCommands = false;
        @JsonProperty("allow-mcp-tools") public boolean allowMcpTools = false;
        @JsonProperty("auto-review") public boolean autoReview = true;
        @JsonProperty("review-timeout-seconds") public int reviewTimeoutSeconds = 10;
        @JsonProperty("max-auto-reviews") public int maxAutoReviews = 8;
    }

    public static final class Mcp {
        public Map<String, McpServer> servers = Map.of();
        @JsonProperty("max-message-bytes") public int maxMessageBytes = 1_048_576;
        @JsonProperty("max-schema-bytes") public int maxSchemaBytes = 65_536;
        @JsonProperty("max-tools") public int maxTools = 128;
        @JsonProperty("max-pages") public int maxPages = 16;
    }

    public static final class McpServer {
        public String command;
        public List<String> args = List.of();
        @JsonProperty("environment-env") public Map<String, String> environmentEnv = Map.of();
        @JsonProperty("timeout-seconds") public int timeoutSeconds = 30;
    }

}
