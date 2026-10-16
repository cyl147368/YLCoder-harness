package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import dev.codeharness.core.agent.*;
import dev.codeharness.core.model.*;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerInputTokenCounterTest {
    @Test void chineseMandatoryInstructionsFailLocallyInsteadOfUnderCounting() {
        List<ChatMessage> messages = List.of(ChatMessage.user("修复边界条件并保留中文注释。".repeat(200)));
        assertTrue(new ContextCompactor().estimateTokens(messages) < 1000);
        for (String name : List.of("cl100k", "o200k")) {
            ContextCompactor compactor = new ContextCompactor(TokenizerInputTokenCounter.create(name));
            assertTrue(compactor.estimateTokens(messages) > 1000);
            AtomicInteger calls = new AtomicInteger();
            ChatModel model = request -> { calls.incrementAndGet(); throw new AssertionError("必须在网络请求前失败"); };
            AgentResult result = new CodeAgent(model, new ToolRegistry(List.of()), compactor).resume(messages,
                    new AgentRunOptions("", 2, 1000, 100, 128, () -> false), AgentListener.NOOP);
            assertEquals(AgentResult.Status.FAILED, result.status());
            assertEquals(0, calls.get());
            assertFalse(result.error().contains("修复"));
        }
    }

    @Test void cjkToolResultsShrinkWithinSelectedBudgetWithoutChangingOpaqueCalls() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (EncodingType type : List.of(EncodingType.CL100K_BASE, EncodingType.O200K_BASE)) {
            InputTokenCounter counter = new TokenizerInputTokenCounter(type);
            ContextCompactor compactor = new ContextCompactor(counter);
            ToolCall call = new ToolCall("latest", "read_file", "{\"path\":\"测试.java\"}", Map.of("signature", "opaque-secret-kept"));
            List<ChatMessage> messages = List.of(ChatMessage.user("修复边界"),
                    ChatMessage.assistantToolCalls("inspect", List.of(call)),
                    ChatMessage.toolResult(call, "中文诊断😀\n".repeat(1500)));
            List<ChatMessage> compacted = compactor.compact(messages, 320, 20000);
            assertEquals(messages.getFirst(), compacted.getFirst());
            assertEquals(call, compacted.get(1).toolCalls().getFirst());
            assertEquals("latest", compacted.getLast().toolCallId());
            assertTrue(counter.messageTokens(compacted) <= 320);
            // 对整个数组独立计数，与计数器逐消息分别计入封装开销的求和方式不同。
            int raw = Encodings.newLazyEncodingRegistry().getEncoding(type).countTokensOrdinary(json.writeValueAsString(compacted));
            assertTrue(raw <= 320, "序列化 CJK 历史超过本地 tokenizer 预算");
            assertEquals(compacted, compactor.compact(compacted, 320, 20000));
            String text = compacted.getLast().content();
            assertTrue(text.isEmpty() || !Character.isLowSurrogate(text.charAt(0)));
            assertTrue(text.isEmpty() || !Character.isHighSurrogate(text.charAt(text.length() - 1)));
        }
    }

    @Test void schemasAndOpaqueMetadataAreCountedAndRequiredContentNeverGetsTruncated() {
        InputTokenCounter counter = TokenizerInputTokenCounter.create("cl100k");
        ToolDefinition tool = new ToolDefinition("read", "中文诊断".repeat(300), Map.of("type", "object"));
        assertTrue(counter.toolTokens(List.of(tool)) > ToolSchemaBudget.estimateTokens(List.of(tool)));
        ToolCall call = new ToolCall("call", "read", "{}", Map.of("signature", "密钥😀".repeat(300)));
        List<ChatMessage> history = List.of(ChatMessage.user("task"), ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "ok"));
        ContextCompactor compactor = new ContextCompactor(counter);
        assertThrows(IllegalArgumentException.class, () -> compactor.compact(history, 400, 128));
        assertEquals(call, history.get(1).toolCalls().getFirst());
        assertEquals(0, counter.toolTokens(List.of()));
    }

    @Test void agentReservesSelectedTokenizerSchemaCostBeforeSendingRequests() {
        var counter = TokenizerInputTokenCounter.create("cl100k");
        ToolDefinition schema = new ToolDefinition("fixture", "中文诊断".repeat(500), Map.of("type", "object"));
        var tool = new dev.codeharness.core.tool.Tool() {
            @Override public ToolDefinition definition() { return schema; }
            @Override public String execute(String json) { fail("工具不得执行"); return ""; }
        };
        AtomicInteger requests = new AtomicInteger();
        ChatModel model = request -> { requests.incrementAndGet(); throw new AssertionError("schema 必须在本地失败"); };
        AgentResult result = new CodeAgent(model, new ToolRegistry(List.of(tool)), new ContextCompactor(counter))
                .run("task", new AgentRunOptions("", 2, 1000, 100, 128, () -> false));
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertTrue(result.error().contains("工具结构超过"));
        assertEquals(0, requests.get());
    }

    @Test void boundedCacheEvictionDoesNotChangeCounts() {
        var counter = new TokenizerInputTokenCounter(EncodingType.O200K_BASE);
        ChatMessage original = ChatMessage.user("第一条消息😀");
        int first = counter.messageTokens(List.of(original));
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 300; i++) history.add(ChatMessage.assistant("message " + i));
        int full = counter.messageTokens(history);
        assertEquals(full, counter.messageTokens(history));
        assertEquals(first, counter.messageTokens(List.of(original)));
        assertEquals(first, counter.messageTokens(List.of(ChatMessage.user(original.content()))));
    }

    @Test void invalidCounterValuesAreRejectedWithoutEchoingValues() {
        for (String name : new String[] {null, "unknown-secret", "CL100K"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TokenizerInputTokenCounter.create(name));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test void largeRequiredMessageMetadataIsCountedAndFailsBeforeModelRequest() {
        Map<String, String> metadata = Map.of("fixture.opaque", "opaque-private-value-密钥😀".repeat(900));
        List<ChatMessage> history = List.of(ChatMessage.user("task"), ChatMessage.assistant("可见答案", metadata));
        for (String name : List.of("utf16", "cl100k", "o200k")) {
            InputTokenCounter counter = TokenizerInputTokenCounter.create(name);
            ContextCompactor compactor = new ContextCompactor(counter);
            int withMetadata = counter.messageTokens(history);
            int withoutMetadata = counter.messageTokens(List.of(history.getFirst(), ChatMessage.assistant("可见答案")));
            assertTrue(withMetadata > withoutMetadata + 1000, name);
            assertEquals(history, compactor.compact(history, withMetadata + 32, 128), "不透明证据能放入预算时必须保持不变");
            AtomicInteger requests = new AtomicInteger();
            AgentResult result = new CodeAgent(request -> { requests.incrementAndGet(); return ChatResponse.text("unexpected"); },
                    ToolRegistry.empty(), compactor).resume(history,
                    new AgentRunOptions("", 2, 1000, 100, 128, () -> false), AgentListener.NOOP);
            assertEquals(AgentResult.Status.FAILED, result.status(), name);
            assertEquals(0, requests.get(), "必需元数据过大时必须在厂商请求前失败");
            assertFalse(result.error().contains("opaque-private-value"));
            assertEquals(metadata, history.getLast().providerMetadata());
        }
    }

    @Test void metadataBearingVisibleContentCannotBeClippedToFit() {
        List<ChatMessage> history = List.of(ChatMessage.user("task"),
                ChatMessage.assistant("必需的可见重放文本。".repeat(500), Map.of("fixture.opaque", "signature")));
        for (String name : List.of("utf16", "cl100k", "o200k")) {
            ContextCompactor compactor = new ContextCompactor(TokenizerInputTokenCounter.create(name));
            assertThrows(IllegalArgumentException.class, () -> compactor.compact(history, 300, 128), name);
            assertEquals("必需的可见重放文本。".repeat(500), history.getLast().content());
            assertEquals(Map.of("fixture.opaque", "signature"), history.getLast().providerMetadata());
        }
    }
}
