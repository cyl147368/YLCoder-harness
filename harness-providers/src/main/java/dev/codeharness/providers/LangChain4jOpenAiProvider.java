package dev.codeharness.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用 LangChain4j 1.22 的 OpenAI Chat Completions 协议适配器。 */
public final class LangChain4jOpenAiProvider implements ModelProvider {
    @Override public String id() { return "langchain4j-openai"; }

    @Override public ChatModel create(ProviderConfig config) {
        if (!id().equals(config.providerId())) throw new IllegalArgumentException("厂商 ID 不受支持");
        return new Model(config);
    }

    private static final class Model implements ChatModel {
        private final OpenAiChatModel delegate;
        private final OpenAiStreamingChatModel streamingDelegate;
        private final Duration timeout;

        private Model(ProviderConfig config) {
            timeout = config.timeout();
            Map<String, String> headers = new LinkedHashMap<>();
            config.headers().forEach((name, value) -> headers.put(
                    "Authorization".equalsIgnoreCase(name) ? "Authorization" : name, value));
            var builder = OpenAiChatModel.builder()
                    .modelName(config.model())
                    .timeout(config.timeout())
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .customHeaders(headers)
                    .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(
                            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)));
            if (config.baseUri() != null) builder.baseUrl(config.baseUri().toString());
            if (config.apiKey() != null && !config.apiKey().isBlank()) builder.apiKey(config.apiKey());
            try {
                delegate = builder.build();
                var streamingBuilder = OpenAiStreamingChatModel.builder()
                        .modelName(config.model()).timeout(config.timeout())
                        .logRequests(false).logResponses(false).customHeaders(headers)
                        .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(
                                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)));
                if (config.baseUri() != null) streamingBuilder.baseUrl(config.baseUri().toString());
                if (config.apiKey() != null && !config.apiKey().isBlank()) streamingBuilder.apiKey(config.apiKey());
                // 流式 SDK 仅发起一次请求，未暴露重试选项。
                streamingDelegate = streamingBuilder.build();
            } catch (RuntimeException failure) {
                throw new ProviderException("LangChain4j OpenAI 配置无效");
            }
        }

        @Override public ChatResponse complete(ChatRequest request) {
            try {
                return response(delegate.chat(request(request)));
            } catch (ProviderException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw redactedFailure(failure);
            }
        }

        @Override public ChatResponse complete(ChatRequest request, Consumer<String> onTextDelta) {
            if (onTextDelta == null) return complete(request);
            if (Thread.currentThread().isInterrupted()) throw new ProviderException("LangChain4j OpenAI 请求已中断");
            CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> completed = new CompletableFuture<>();
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            AtomicBoolean active = new AtomicBoolean(true);
            try {
                streamingDelegate.chat(request(request)).subscribe(new Flow.Subscriber<ChatModelStreamingEvent>() {
                    @Override public void onSubscribe(Flow.Subscription incoming) {
                        if (!subscription.compareAndSet(null, incoming) || !active.get()) {
                            incoming.cancel();
                            return;
                        }
                        incoming.request(Long.MAX_VALUE);
                    }
                    @Override public void onNext(ChatModelStreamingEvent event) {
                        if (!active.get()) return;
                        if (event instanceof PartialResponse partial && partial.text() != null && !partial.text().isEmpty()) {
                            try { onTextDelta.accept(partial.text()); }
                            catch (RuntimeException ignored) { /* 观察者失败不能停止生成。 */ }
                        } else if (event instanceof CompleteResponse complete) {
                            completed.complete(complete.chatResponse());
                        }
                    }
                    @Override public void onError(Throwable failure) { completed.completeExceptionally(failure); }
                    @Override public void onComplete() {
                        if (!completed.isDone()) completed.completeExceptionally(
                                new ProviderException("LangChain4j OpenAI 返回了不完整的流"));
                    }
                });
                long millis;
                try { millis = Math.max(1, timeout.toMillis()); }
                catch (ArithmeticException overflow) { millis = Long.MAX_VALUE; }
                return response(completed.get(millis, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ProviderException("LangChain4j OpenAI 请求已中断");
            } catch (TimeoutException timedOut) {
                throw new ProviderException("LangChain4j OpenAI 流式请求超时");
            } catch (ExecutionException failure) {
                throw redactedFailure(failure.getCause());
            } catch (ProviderException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw redactedFailure(failure);
            } finally {
                active.set(false);
                Flow.Subscription current = subscription.get();
                if (current != null) current.cancel();
            }
        }

        private static dev.langchain4j.model.chat.request.ChatRequest request(ChatRequest request) {
            var parameters = OpenAiChatRequestParameters.builder()
                    .maxCompletionTokens(request.maxOutputTokens())
                    .toolSpecifications(request.tools().stream().map(Model::tool).toList()).build();
            return dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(request.messages().stream().map(Model::message).toList())
                    .parameters(parameters).build();
        }

        private static ChatResponse response(dev.langchain4j.model.chat.response.ChatResponse response) {
                if (response.aiMessage() == null || response.finishReason() == null) {
                    throw new ProviderException("LangChain4j OpenAI 返回了不完整的响应");
                }
                List<ToolCall> calls = response.aiMessage().toolExecutionRequests().stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.arguments())).toList();
                FinishReason finish = switch (response.finishReason()) {
                    case STOP -> calls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
                    case TOOL_EXECUTION -> FinishReason.TOOL_CALLS;
                    case LENGTH -> FinishReason.LENGTH;
                    case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
                    case OTHER -> throw new ProviderException("LangChain4j OpenAI 返回了不支持的结束原因");
                };
                if (finish == FinishReason.TOOL_CALLS && calls.isEmpty()) {
                    throw new ProviderException("LangChain4j OpenAI 遗漏了工具调用");
                }
                var usage = response.tokenUsage();
                long cached = usage instanceof OpenAiTokenUsage openAi && openAi.inputTokensDetails() != null
                        ? count(openAi.inputTokensDetails().cachedTokens()) : 0;
                TokenUsage counts = usage == null ? TokenUsage.ZERO
                        : new TokenUsage(count(usage.inputTokenCount()), count(usage.outputTokenCount()), cached);
                return new ChatResponse(response.aiMessage().text(), calls, counts, finish,
                        UsageReporting.metadata(usage != null && UsageReporting.reported(usage.inputTokenCount()),
                                usage != null && UsageReporting.reported(usage.outputTokenCount()),
                                usage instanceof OpenAiTokenUsage openAi && openAi.inputTokensDetails() != null
                                        && UsageReporting.reported(openAi.inputTokensDetails().cachedTokens())));
        }

        private static ProviderException redactedFailure(Throwable failure) {
            // SDK 异常可能含请求、响应正文及凭据，绝不暴露其原始原因。
            int status = httpStatus(failure);
            return new ProviderException(status == 0 ? "LangChain4j OpenAI 请求失败"
                    : "LangChain4j OpenAI 请求失败，HTTP 状态码 " + status, status);
        }

        private static dev.langchain4j.data.message.ChatMessage message(ChatMessage source) {
            return switch (source.role()) {
                case SYSTEM -> SystemMessage.from(source.content());
                case USER -> UserMessage.from(source.content());
                case TOOL -> ToolExecutionResultMessage.from(source.toolCallId(), source.toolName(), source.content());
                case ASSISTANT -> {
                    List<ToolExecutionRequest> calls = source.toolCalls().stream().map(call -> ToolExecutionRequest.builder()
                            .id(call.id()).name(call.name()).arguments(call.argumentsJson()).build()).toList();
                    yield calls.isEmpty() ? AiMessage.from(source.content())
                            : source.content().isEmpty() ? AiMessage.from(calls) : AiMessage.from(source.content(), calls);
                }
            };
        }

        private static ToolSpecification tool(ToolDefinition definition) {
            var schema = new LinkedHashMap<>(definition.inputSchema());
            schema.putIfAbsent("type", "object");
            if (!"object".equals(schema.get("type"))) throw new ProviderException("工具结构必须描述 JSON 对象");
            try {
                var spec = Map.of("name", definition.name(), "description", definition.description(), "parameters", schema);
                return ToolSpecification.fromJson(AbstractHttpChatModel.JSON.writeValueAsString(spec));
            } catch (JsonProcessingException failure) {
                throw new ProviderException("无法编码工具结构");
            }
        }

        private static long count(Integer count) { return count == null ? 0 : count; }

        private static int httpStatus(Throwable error) {
            for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
                if (error instanceof HttpException http) return http.statusCode();
            }
            return 0;
        }
    }
}
