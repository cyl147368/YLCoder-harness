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
        assertEquals(10, config.permissions.reviewTimeoutSeconds);
        assertEquals(8, config.permissions.maxAutoReviews);
        assertEquals("https://your-provider.example/v1", config.baseUrl);
        assertEquals("", config.apiKey);
        assertEquals("HARNESS_API_KEY", config.apiKeyEnv);
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
                "agent:\n  context-budget-tokens: 0\n", "provider: null\n"}) {
            Files.writeString(config, invalid);
            assertThrows(IOException.class, () -> ConfigLoader.load(temporary, config));
        }
    }

    @Test void validatesTokenizerSelectionWithoutEchoingInvalidValues() throws Exception {
        Path config = temporary.resolve("counter.yaml");
        for (String name : new String[] {"utf16", "cl100k", "o200k"}) {
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
}
