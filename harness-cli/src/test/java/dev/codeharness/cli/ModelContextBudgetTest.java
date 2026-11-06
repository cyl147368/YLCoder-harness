package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelContextBudgetTest {
    @TempDir Path temporary;

    @Test void 默认自动分词选择已知模型编码且自定义模型不猜容量() {
        HarnessConfig.Agent agent = new HarnessConfig.Agent();
        assertEquals("auto", agent.tokenCounter);
        assertEquals("o200k", TokenizerInputTokenCounter.selectedEncoding("company/gpt-4.1-mini"));
        assertEquals("cl100k", TokenizerInputTokenCounter.selectedEncoding("gpt-4-0613"));
        assertEquals("conservative", TokenizerInputTokenCounter.selectedEncoding("my-custom-model"));
        var resolution = ModelContextBudget.resolve(agent, "my-custom-model");
        assertEquals(48000, resolution.inputBudgetTokens());
        assertEquals(53048, resolution.contextWindowTokens());
        assertEquals("自定义输入预算", resolution.windowSource());
    }

    @Test void 显式模型窗口优先并扣除输出推理和安全预留() {
        HarnessConfig.Agent agent = new HarnessConfig.Agent();
        agent.contextWindowTokens = 20000;
        agent.modelContextWindows = Map.of("my-model", 12000);
        agent.maxOutputTokens = 3000;
        agent.reasoningReserveTokens = 1000;
        agent.contextSafetyMarginTokens = 2000;
        var selected = ModelContextBudget.resolve(agent, "my-model");
        assertEquals(6000, selected.inputBudgetTokens());
        assertEquals(12000, selected.contextWindowTokens());
        assertEquals("模型配置", selected.windowSource());
        var other = ModelContextBudget.resolve(agent, "other-model");
        assertEquals(14000, other.inputBudgetTokens());
        assertEquals("通用配置", other.windowSource());
        agent.contextBudgetTokens = 2000;
        assertEquals(2000, ModelContextBudget.resolve(agent, "my-model").inputBudgetTokens());
    }

    @Test void 切换较小窗口模型会缩小输入预算() {
        HarnessConfig.Agent agent = new HarnessConfig.Agent();
        assertEquals(48000, ModelContextBudget.resolve(agent, "gpt-4.1-mini").inputBudgetTokens());
        assertEquals(3144, ModelContextBudget.resolve(agent, "gpt-4").inputBudgetTokens());
        agent.contextWindowTokens = 3000;
        assertThrows(IllegalArgumentException.class, () -> ModelContextBudget.resolve(agent, "gpt-4"));
    }

    @Test void 大数值预留不会溢出为正输入预算() {
        HarnessConfig.Agent agent = new HarnessConfig.Agent();
        agent.contextWindowTokens = Integer.MAX_VALUE;
        agent.maxOutputTokens = Integer.MAX_VALUE;
        agent.reasoningReserveTokens = Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class, () -> ModelContextBudget.resolve(agent, "custom"));
        agent.maxOutputTokens = null;
        agent.reasoningReserveTokens = 0;
        assertTrue(ModelContextBudget.resolve(agent, "custom").inputBudgetTokens() > 0);
    }

    @Test void 配置加载支持自定义窗口和摘要模型() throws Exception {
        Path file = temporary.resolve("context.yaml");
        Files.writeString(file, """
                model: custom
                agent:
                  token-counter: auto
                  context-window-tokens: 16384
                  model-context-windows:
                    custom: 32768
                  context-safety-margin-tokens: 1024
                  reasoning-reserve-tokens: 2048
                  context-semantic: true
                  context-trigger-ratio: 0.8
                  context-target-ratio: 0.6
                  context-recent-turns: 3
                  context-summary-max-tokens: 1200
                  context-summarizer:
                    model: summarizer
                """);
        HarnessConfig config = ConfigLoader.load(temporary, file);
        assertEquals(26696, ModelContextBudget.resolve(config.agent, config.model).inputBudgetTokens());
        assertEquals("summarizer", config.agent.contextSummarizer.model);
        assertEquals(0.8, config.agent.contextTriggerRatio);
        assertEquals(3, config.agent.contextRecentTurns);
    }

    @Test void 无效新配置在网络前拒绝且诊断不回显秘密() throws Exception {
        Path file = temporary.resolve("bad.yaml");
        for (String invalid : new String[]{
                "context-window-tokens: 0", "model-context-windows: null",
                "model-context-windows: {custom: 0}", "context-safety-margin-tokens: -1",
                "reasoning-reserve-tokens: -1", "context-target-ratio: 0.9",
                "context-trigger-ratio: 0", "context-trigger-ratio: .nan",
                "context-recent-turns: 0", "context-summary-max-tokens: 63",
                "context-summarizer: null", "context-summarizer: {base-url: 'https://secret-fixture@invalid.example/v1'}"}) {
            Files.writeString(file, "agent:\n  " + invalid + "\n");
            var error = assertThrows(java.io.IOException.class, () -> ConfigLoader.load(temporary, file), invalid);
            assertFalse(error.toString().contains("secret-fixture"));
        }
    }
}
