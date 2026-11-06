package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path temporary;

    @Test void checkedInExampleLoadsWithoutIgnoredOptions() throws Exception {
        Path example = Path.of("../config.example.yaml").toAbsolutePath().normalize();
        if (!Files.exists(example)) example = Path.of("config.example.yaml").toAbsolutePath();
        HarnessConfig config = ConfigLoader.load(temporary, example);
        assertEquals("openai-compatible", config.provider);
        assertEquals(30, config.agent.maxIterations);
        assertEquals(1, config.agent.parallelReads);
        assertEquals(3000, config.agent.maxOutputTokens);
        assertFalse(config.permissions.allowCommands);
        assertTrue(config.permissions.autoReview);
        assertEquals("review", config.permissions.effectiveMode());
        assertNotNull(config.permissions.reviewer);
        assertEquals(10, config.permissions.reviewTimeoutSeconds);
        assertEquals(8, config.permissions.maxAutoReviews);
        assertEquals("https://your-provider.example/v1", config.baseUrl);
        assertEquals("", config.apiKey);
        assertEquals("HARNESS_API_KEY", config.apiKeyEnv);
        assertTrue(config.trace.enabled);
        assertEquals(2000, config.trace.toolResultMaxChars);
    }

    @Test void rejectsUnknownFieldsAndDoesNotEchoValues() throws Exception {
        Path config = temporary.resolve("bad.yaml");
        Files.writeString(config, "api-key: super-secret-example\nunknown-option: super-secret-example\n");
        IOException error = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
        assertFalse(error.toString().contains("super-secret-example"));
        assertNull(error.getCause());
    }

    @Test void loadsPrivateInlineCredentialAndMasksMalformedYaml() throws Exception {
        Path config = temporary.resolve("private.yaml");
        Files.writeString(config, "api-key: inline-fixture-secret\n");
        assertEquals("inline-fixture-secret", ConfigLoader.load(temporary, config).apiKey);
        Files.writeString(config, "api-key: [inline-fixture-secret\n");
        IOException error = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
        assertFalse(error.toString().contains("inline-fixture-secret"));
        assertNull(error.getCause());
    }

    @Test void projectCannotRedirectCredentialsOrEnableCommandsWithoutExplicitTrust() throws Exception {
        Path repo = temporary.resolve("repo");
        Files.createDirectories(repo.resolve(".harness"));
        Path project = repo.resolve(".harness/config.yaml");
        Files.writeString(project, "provider: openai\nbase-url: https://untrusted.invalid/v1\napi-key-env: PRIVATE_TOKEN\npermissions:\n  allow-commands: true\n");
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.resolve("home").toString());
            HarnessConfig untrusted = ConfigLoader.load(repo, null);
            assertNull(untrusted.baseUrl);
            assertNull(untrusted.apiKeyEnv);
            assertFalse(untrusted.permissions.allowCommands);
            HarnessConfig trusted = ConfigLoader.load(repo, null, true);
            assertEquals("https://untrusted.invalid/v1", trusted.baseUrl);
            assertTrue(trusted.permissions.allowCommands);
            assertTrue(ConfigLoader.load(repo, project).permissions.allowCommands);
        } finally { System.setProperty("user.home", oldHome); }
    }

    @Test void rejectsNullSectionsAndInvalidBudgets() throws Exception {
        Path config = temporary.resolve("bad.yaml");
        for (String invalid : new String[]{"agent: null\n", "permissions: null\n",
                "agent:\n  context-budget-tokens: 0\n", "provider: null\n", "models: null\n",
                "models:\n  - ' '\n"}) {
            Files.writeString(config, invalid);
            assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
        }
    }

    @Test void validatesTokenizerSelectionWithoutEchoingInvalidValues() throws Exception {
        Path config = temporary.resolve("counter.yaml");
        for (String name : new String[] {"auto", "conservative", "utf16", "cl100k", "o200k"}) {
            Files.writeString(config, "agent:\n  token-counter: " + name + "\n");
            assertEquals(name, ConfigLoader.load(temporary, config).agent.tokenCounter);
        }
        for (String name : new String[] {"null", "unknown-secret"}) {
            Files.writeString(config, "agent:\n  token-counter: " + name + "\n");
            IOException failure = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
            assertFalse(failure.toString().contains("unknown-secret"));
        }
    }

    @Test void validatesParallelReadsRangeAndRequiresProjectTrust() throws Exception {
        Path repo = Files.createDirectories(temporary.resolve("parallel-repo/.harness")).getParent();
        Path config = repo.resolve(".harness/config.yaml");
        for (int value : new int[]{1, 4, 8}) {
            Files.writeString(config, "agent:\n  parallel-reads: " + value + "\n");
            assertEquals(value, ConfigLoader.load(repo, config).agent.parallelReads);
        }
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.resolve("empty-user-home").toString());
            assertEquals(1, ConfigLoader.load(repo, null).agent.parallelReads);
            assertEquals(8, ConfigLoader.load(repo, null, true).agent.parallelReads);
        } finally { System.setProperty("user.home", oldHome); }
        for (String value : new String[]{"0", "9", "-1", "null"}) {
            Files.writeString(config, "agent:\n  parallel-reads: " + value + "\n");
            IOException failure = assertThrows(IOException.class, () -> ConfigLoader.load(repo, config));
            assertTrue(failure.getMessage().contains("parallel-reads"));
        }
    }

    @Test void mcpLaunchConfigurationAndPermissionRequireTrustedOrExplicitSelection() throws Exception {
        Path repo = temporary.resolve("mcp-repo");
        Files.createDirectories(repo.resolve(".harness"));
        Path project = repo.resolve(".harness/config.yaml");
        Files.writeString(project, "permissions:\n  allow-mcp-tools: true\nmcp:\n  servers:\n    fixture:\n      command: java\n      args: ['-version']\n      environment-env:\n        SERVICE_TOKEN: CONFIGURED_TEST_TOKEN\n");
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.resolve("no-user-config").toString());
            HarnessConfig ignored = ConfigLoader.load(repo, null);
            assertTrue(ignored.mcp.servers.isEmpty());
            assertFalse(ignored.permissions.allowMcpTools);
            HarnessConfig trusted = ConfigLoader.load(repo, null, true);
            assertTrue(trusted.permissions.allowMcpTools);
            assertFalse(trusted.permissions.allowCommands);
            assertEquals(java.util.List.of("-version"), trusted.mcp.servers.get("fixture").args);
            assertEquals("CONFIGURED_TEST_TOKEN", ConfigLoader.load(repo, project).mcp.servers.get("fixture").environmentEnv.get("SERVICE_TOKEN"));
        } finally { System.setProperty("user.home", oldHome); }
    }

    @Test void rejectsInvalidMcpLimitsAndServerFieldsWithoutEchoingSecrets() throws Exception {
        Path config = temporary.resolve("bad-mcp.yaml");
        for (String invalid : new String[]{"mcp: null\n", "mcp:\n  servers: null\n", "mcp:\n  max-message-bytes: 1\n",
                "mcp:\n  servers:\n    fixture:\n      command: null\n", "mcp:\n  servers:\n    fixture:\n      command: java\n      args: null\n",
                "mcp:\n  servers:\n    fixture:\n      command: java\n      timeout-seconds: 0\n"}) {
            Files.writeString(config, invalid);
            assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
        }
    }

    @Test void reviewBudgetsAreBoundedAndOnlyTrustedConfigChangesReviewPolicy() throws Exception {
        Path repo = Files.createDirectories(temporary.resolve("review-repo/.harness")).getParent();
        Path project = repo.resolve(".harness/config.yaml");
        Files.writeString(project, "permissions:\n  auto-review: false\n  review-timeout-seconds: 3\n  max-auto-reviews: 2\n");
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.resolve("empty-review-home").toString());
            HarnessConfig ignored = ConfigLoader.load(repo, null);
            assertTrue(ignored.permissions.autoReview);
            assertEquals(10, ignored.permissions.reviewTimeoutSeconds);
            assertEquals(8, ignored.permissions.maxAutoReviews);
            for (HarnessConfig selected : new HarnessConfig[]{ConfigLoader.load(repo, project), ConfigLoader.load(repo, null, true)}) {
                assertFalse(selected.permissions.autoReview);
                assertEquals(3, selected.permissions.reviewTimeoutSeconds);
                assertEquals(2, selected.permissions.maxAutoReviews);
            }
        } finally { System.setProperty("user.home", oldHome); }
        for (String invalid : new String[]{"review-timeout-seconds: 0", "review-timeout-seconds: 31",
                "review-timeout-seconds: null", "max-auto-reviews: 0", "max-auto-reviews: 33", "max-auto-reviews: null"}) {
            Files.writeString(project, "permissions:\n  " + invalid + "\n");
            IOException failure = assertThrows(IOException.class, () -> ConfigLoader.load(repo, project));
            assertTrue(failure.getMessage().contains("permissions.review-timeout-seconds"));
        }
    }

    @Test void 可加载独立审核连接和显式模式() throws Exception {
        Path file = temporary.resolve("reviewer.yaml");
        Files.writeString(file, """
                permissions:
                  mode: manual
                  allow-commands: true
                  reviewer:
                    provider: openai-compatible
                    model: small-review-model
                    base-url: https://review.example/v1
                    api-key: review-inline-fixture
                    api-key-env: REVIEW_API_KEY
                    header-env:
                      X-Review: REVIEW_HEADER
                """);
        HarnessConfig config = ConfigLoader.load(temporary, file);
        assertEquals("manual", config.permissions.effectiveMode());
        assertEquals("openai-compatible", config.permissions.reviewer.provider);
        assertEquals("small-review-model", config.permissions.reviewer.model);
        assertEquals("https://review.example/v1", config.permissions.reviewer.baseUrl);
        assertEquals("review-inline-fixture", config.permissions.reviewer.apiKey);
        assertEquals("REVIEW_API_KEY", config.permissions.reviewer.apiKeyEnv);
        assertEquals(java.util.Map.of("X-Review", "REVIEW_HEADER"), config.permissions.reviewer.headerEnv);
    }

    @Test void 审核配置只从显式或可信文件载入() throws Exception {
        Path repo = Files.createDirectories(temporary.resolve("reviewer-repo/.harness")).getParent();
        Path file = repo.resolve(".harness/config.yaml");
        Files.writeString(file, "permissions:\n  mode: allow-all\n  reviewer:\n    base-url: https://review.example/v1\n");
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.resolve("reviewer-home").toString());
            HarnessConfig ignored = ConfigLoader.load(repo, null);
            assertEquals("review", ignored.permissions.effectiveMode());
            assertNull(ignored.permissions.reviewer.baseUrl);
            assertEquals("allow-all", ConfigLoader.load(repo, null, true).permissions.effectiveMode());
            assertEquals("https://review.example/v1", ConfigLoader.load(repo, file).permissions.reviewer.baseUrl);
        } finally { System.setProperty("user.home", oldHome); }
    }

    @Test void 审核模式与嵌套字段错误不会回显秘密() throws Exception {
        Path file = temporary.resolve("bad-reviewer.yaml");
        for (String invalid : new String[]{
                "mode: secret-fixture", "mode: ''", "reviewer: null",
                "reviewer:\n    provider: ''", "reviewer:\n    model: ''",
                "reviewer:\n    base-url: https://secret-fixture@review.example/v1",
                "reviewer:\n    base-url: https://review.example/v1?key=secret-fixture",
                "reviewer:\n    base-url: secret-fixture",
                "reviewer:\n    base-url: ''",
                "reviewer:\n    api-key-env: secret-fixture",
                "reviewer:\n    header-env:\n      'Bad Header': SECRET_FIXTURE",
                "reviewer:\n    header-env:\n      X-Review: null",
                "reviewer:\n    header-env:\n      X-Review: secret-fixture",
                "reviewer:\n    unknown: secret-fixture"}) {
            Files.writeString(file, "permissions:\n  " + invalid + "\n");
            IOException error = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, file));
            assertFalse(error.toString().contains("secret-fixture"));
            assertFalse(error.toString().contains("SECRET_FIXTURE"));
            assertNull(error.getCause());
        }
    }

    @Test void 未指定模式继续解析旧权限开关() throws Exception {
        Path file = temporary.resolve("legacy-review.yaml");
        Files.writeString(file, "permissions: {}\n");
        assertEquals("review", ConfigLoader.load(temporary, file).permissions.effectiveMode());
        Files.writeString(file, "permissions:\n  auto-review: false\n");
        assertEquals("manual", ConfigLoader.load(temporary, file).permissions.effectiveMode());
        Files.writeString(file, "permissions:\n  allow-commands: true\n  auto-review: false\n");
        assertEquals("allow-all", ConfigLoader.load(temporary, file).permissions.effectiveMode());
    }

    @Test void 请求格式可以独立配置且省略时保留旧行为() throws Exception {
        Path file = temporary.resolve("api-format.yaml");
        Files.writeString(file, "provider: openai-compatible\n");
        HarnessConfig legacy = ConfigLoader.load(temporary, file);
        assertNull(legacy.apiFormat);
        assertNull(legacy.permissions.reviewer.apiFormat);
        for (String format : new String[]{"chat", "responses"}) {
            Files.writeString(file, "provider: openai-compatible\napi-format: " + format
                    + "\npermissions:\n  reviewer:\n    api-format: responses\n");
            HarnessConfig selected = ConfigLoader.load(temporary, file);
            assertEquals("openai-compatible", selected.provider);
            assertEquals(format, selected.apiFormat);
            assertEquals("responses", selected.permissions.reviewer.apiFormat);
        }
    }

    @Test void 错误请求格式在加载时给出脱敏诊断() throws Exception {
        Path file = temporary.resolve("invalid-api-format.yaml");
        for (String prefix : new String[]{"", "permissions:\n  reviewer:\n    "}) {
            for (String invalid : new String[]{"''", "unknown-fixture-secret"}) {
                Files.writeString(file, prefix + "api-format: " + invalid + "\n");
                IOException failure = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, file));
                assertTrue(failure.getMessage().contains("api-format"));
                assertFalse(failure.toString().contains("unknown-fixture-secret"));
                assertNull(failure.getCause());
            }
        }
    }
    @Test void 执行记录默认开启并校验摘要长度而不回显无效内容() throws Exception {
        Path file = temporary.resolve("trace.yaml");
        Files.writeString(file, "provider: openai-compatible\n");
        assertTrue(ConfigLoader.load(temporary, file).trace.enabled);
        Files.writeString(file, "trace:\n  enabled: false\n  tool-result-max-chars: 256\n");
        HarnessConfig disabled = ConfigLoader.load(temporary, file);
        assertFalse(disabled.trace.enabled);
        assertEquals(256, disabled.trace.toolResultMaxChars);
        for (String invalid : new String[] {"trace: null\n", "trace:\n  tool-result-max-chars: 127\n",
                "trace:\n  tool-result-max-chars: 32001\n", "trace:\n  directory: private-should-not-echo\n"}) {
            Files.writeString(file, invalid);
            IOException failure = assertThrows(IOException.class, () -> ConfigLoader.load(temporary, file));
            assertFalse(failure.toString().contains("private-should-not-echo"));
        }
    }
}
