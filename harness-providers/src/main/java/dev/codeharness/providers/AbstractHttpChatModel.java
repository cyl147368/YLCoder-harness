package dev.codeharness.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

abstract class AbstractHttpChatModel implements ChatModel {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected final ProviderConfig config;
    protected final HttpClient client;

    AbstractHttpChatModel(ProviderConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    protected abstract URI endpoint();
    protected abstract Map<String, String> protocolHeaders();
    protected abstract ObjectNode encode(ChatRequest request);
    protected abstract ChatResponse decode(JsonNode payload);

    @Override
    public final ChatResponse complete(ChatRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(encode(request))));
            protocolHeaders().forEach(builder::setHeader);
            config.headers().forEach(builder::setHeader);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException(config.providerId() + " 请求失败，HTTP 状态码 " + response.statusCode(), response.statusCode());
            }
            JsonNode payload = JSON.readTree(response.body());
            if (payload == null || !payload.isObject() || payload.hasNonNull("error")) {
                throw new ProviderException(config.providerId() + " 返回了无效的响应结构");
            }
            return decode(payload);
        } catch (ProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(config.providerId() + " 请求已中断");
        } catch (IOException | RuntimeException e) {
            // 解析器、HTTP 消息及异常原因都可能包含请求 URL 或载荷，不能直接暴露。
            throw new ProviderException(config.providerId() + " 请求失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    protected URI resolve(String path) {
        URI base = config.baseUri();
        if (base == null) base = defaultBaseUri();
        String text = base.toString();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(text + normalizedPath);
    }

    protected URI defaultBaseUri() { throw new IllegalArgumentException("此厂商需要配置 baseUri：" + config.providerId()); }

    protected static JsonNode at(JsonNode node, String... path) {
        JsonNode current = node;
        for (String part : path) {
            if (current == null) return null;
            if (current.isArray()) {
                try { current = current.path(Integer.parseInt(part)); }
                catch (NumberFormatException e) { return current.path(part); }
            } else current = current.path(part);
        }
        return current;
    }

    protected static String text(JsonNode node, String fallback) {
        return node == null || node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }
}
