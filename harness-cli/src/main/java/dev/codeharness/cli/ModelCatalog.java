package dev.codeharness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.providers.ProviderConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 合并当前、配置、最近会话和兼容网关返回的模型选项；失败只返回可离线使用的候选。 */
final class ModelCatalog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_OPTIONS = 64;

    record Option(String id, String source) {}
    record Result(List<Option> options, String warning) {
        Result {
            options = List.copyOf(options);
        }
    }

    static Result discover(HarnessConfig config, ProviderConfig provider, List<String> recent) {
        var options = new LinkedHashSet<String>();
        var result = new ArrayList<Option>();
        add(result, options, config.model, "当前");
        if (config.models != null) config.models.forEach(model -> add(result, options, model, "配置"));
        if (recent != null) recent.forEach(model -> add(result, options, model, "最近会话"));
        String warning = "";
        if (config.discoverModels) {
            try {
                List<String> discovered = fetch(provider);
                for (String model : discovered) add(result, options, model, "厂商");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                warning = "模型列表读取已取消；可直接输入模型名，或在配置 models 中添加候选。";
            } catch (IOException | RuntimeException failure) {
                warning = "无法读取厂商模型列表；可直接输入模型名，或在配置 models 中添加候选。";
            }
        }
        return new Result(result.stream().limit(MAX_OPTIONS).toList(), warning);
    }

    private static void add(List<Option> result, LinkedHashSet<String> seen, String value, String source) {
        if (value == null) return;
        String model = value.strip();
        if (model.isEmpty() || model.length() > 256
                || model.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT)
                || !seen.add(model)) return;
        result.add(new Option(model, source));
    }

    private static List<String> fetch(ProviderConfig provider) throws IOException, InterruptedException {
        URI endpoint = modelsEndpoint(provider);
        if (endpoint == null) throw new IOException("没有兼容的模型列表端点");
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (provider.apiKey() != null && !provider.apiKey().isBlank()
                && provider.headers().keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Authorization"))) {
            request.header("Authorization", "Bearer " + provider.apiKey());
        }
        provider.headers().forEach(request::header);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        // 总时限必须覆盖正文读取；仅等待响应头不能限制停滞的分块正文。
        var pending = client.sendAsync(request.GET().build(), ignored -> new LimitedBody());
        byte[] body;
        try {
            HttpResponse<byte[]> response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("模型列表请求失败");
            body = response.body();
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException failure) {
            throw new IOException("模型列表读取失败、超时或超过大小限制");
        } finally {
            pending.cancel(true);
            client.shutdownNow();
        }
        JsonNode root = JSON.readTree(body);
        JsonNode values = root != null && root.isObject() && root.path("data").isArray() ? root.path("data")
                : root != null && root.isObject() && root.path("models").isArray() ? root.path("models")
                : root;
        if (values == null || !values.isArray()) throw new IOException("模型列表结构不受支持");
        var models = new ArrayList<String>();
        for (JsonNode item : values) {
            String id = item.isTextual() ? item.textValue() : item.path("id").isTextual()
                    ? item.path("id").textValue() : item.path("name").textValue();
            if (id != null && !id.isBlank() && id.length() <= 256) models.add(id);
            if (models.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(models);
    }

    private static URI modelsEndpoint(ProviderConfig provider) {
        URI base = provider.baseUri();
        if (base == null) {
            String id = provider.providerId().toLowerCase(Locale.ROOT);
            base = switch (id) {
                case "openai", "openai-responses", "langchain4j-openai" -> URI.create("https://api.openai.com/v1");
                case "ollama" -> URI.create("http://localhost:11434/v1");
                default -> null;
            };
        }
        if (base == null) return null;
        String value = base.toString().replaceAll("/+$", "");
        return URI.create(value + "/models");
    }

    /** 读取期间限制内存占用，超限立即取消订阅，不继续缓冲厂商正文。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("模型列表过大"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private ModelCatalog() {}
}
