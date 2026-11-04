package dev.codeharness.cli;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;

/** 面向用户的 YAML 配置。支持私有配置内密钥和环境变量引用，禁止整体记录配置。 */
public final class HarnessConfig {
    /** 仅为兼容旧 YAML 保留；CLI 工作区由启动目录或 --workspace 选择，此值不参与选择。 */
    public String workspace = ".";
    public String provider = "openai";
    @JsonProperty("api-format") public String apiFormat;
    public String model = "gpt-4.1-mini";
    @JsonProperty("base-url") public String baseUrl;
    @JsonProperty("api-key") public String apiKey;
    @JsonProperty("api-key-env") public String apiKeyEnv;
    /** /model 的显式候选；自动发现失败或厂商不提供模型列表时仍可离线选择。 */
    public List<String> models = List.of();
    @JsonProperty("discover-models") public boolean discoverModels = true;
    @JsonProperty("timeout-seconds") public int timeoutSeconds = 120;
    @JsonProperty("header-env") public Map<String, String> headerEnv = Map.of();
    public Agent agent = new Agent();
    public Permissions permissions = new Permissions();
    public Mcp mcp = new Mcp();
    public Web web = new Web();
    public Trace trace = new Trace();

    /** 本地执行记录独立于会话检查点；重复提示词引用首次记录，工具结果只保留摘要。 */
    public static final class Trace {
        public boolean enabled = true;
        @JsonProperty("tool-result-max-chars") public int toolResultMaxChars = 2000;
    }

    public static final class Web {
        public boolean enabled = true;
        public String provider = "auto";
        @JsonProperty("search-url") public String searchUrl;
        @JsonProperty("timeout-seconds") public int timeoutSeconds = 15;
        @JsonProperty("max-results") public int maxResults = 5;
        @JsonProperty("max-page-chars") public int maxPageChars = 10_000;
    }

    public static final class Agent {
        public boolean stream = true;
        @JsonProperty("stream-fallback") public boolean streamFallback = true;
        /** 旧配置兼容字段；智能体不再按此值截断任务。 */
        @JsonProperty("max-iterations") @Deprecated public int maxIterations = 30;
        @JsonProperty("parallel-reads") public int parallelReads = 1;
        @JsonProperty("context-budget-tokens") public int contextBudgetTokens = 48_000;
        @JsonProperty("token-counter") public String tokenCounter = "utf16";
        @JsonProperty("max-tool-output-chars") public int maxToolOutputChars = 12_000;
        @JsonProperty("max-output-tokens") public Integer maxOutputTokens = 3_000;
    }

    public static final class Permissions {
        /** 未指定时保留旧配置语义；显式模式优先于旧布尔开关。 */
        public String mode;
        @JsonProperty("allow-commands") public boolean allowCommands = false;
        @JsonProperty("allow-mcp-tools") public boolean allowMcpTools = false;
        @JsonProperty("auto-review") public boolean autoReview = true;
        @JsonProperty("review-timeout-seconds") public int reviewTimeoutSeconds = 10;
        @JsonProperty("max-auto-reviews") public int maxAutoReviews = 8;
        public Reviewer reviewer = new Reviewer();

        public String effectiveMode() {
            if (mode == null) return allowCommands ? "allow-all" : autoReview ? "review" : "manual";
            String selected = mode.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (selected) {
                case "review", "manual", "allow-all" -> selected;
                default -> throw new IllegalArgumentException("permissions.mode 必须是 review、manual 或 allow-all");
            };
        }
    }

    /** 审核模型可单独配置；未指定厂商和端点时沿用主模型连接。 */
    public static final class Reviewer {
        public String provider;
        @JsonProperty("api-format") public String apiFormat;
        public String model;
        @JsonProperty("base-url") public String baseUrl;
        @JsonProperty("api-key") public String apiKey;
        @JsonProperty("api-key-env") public String apiKeyEnv;
        /** null 表示继承同一连接的请求头，空映射表示清空。 */
        @JsonProperty("header-env") public Map<String, String> headerEnv;
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
