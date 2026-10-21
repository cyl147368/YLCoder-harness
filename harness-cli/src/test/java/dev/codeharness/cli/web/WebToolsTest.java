package dev.codeharness.cli.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.codeharness.cli.HarnessConfig;
import dev.codeharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WebTools.Page page(URI uri, String type, String body) {
        return new WebTools.Page(uri, type, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test void defaultSearchReturnsCitationsAndCachesIdenticalRequests() throws Exception {
        var config = new HarnessConfig.Web();
        AtomicInteger calls = new AtomicInteger();
        var registry = new WebTools(config, (uri, trusted) -> {
            calls.incrementAndGet();
            assertTrue(uri.toString().contains("%E6%96%B0%E9%97%BB"));
            assertNull(trusted);
            return page(uri, "application/rss+xml", "<rss><channel><item><title>中文新闻</title><link>https://example.com/news</link><description>内容 &amp; 摘要</description></item></channel></rss>");
        }).combine(ToolRegistry.empty());
        var tool = registry.find("web_search").orElseThrow();
        var first = JSON.readTree(tool.execute("{\"query\":\"新闻 2026-10-20\"}"));
        tool.execute("{\"query\":\"新闻 2026-10-20\"}");
        assertEquals(1, calls.get());
        assertEquals("https://example.com/news", first.path("results").get(0).path("url").asText());
        assertTrue(first.path("notice").asText().contains("不等于已核实"));
    }

    @Test void unavailablePrimaryFallsBackAndDecodesSearchRedirects() throws Exception {
        var registry = new WebTools(new HarnessConfig.Web(), (uri, trusted) -> {
            if (uri.getHost().contains("bing")) throw new java.io.IOException("fixture failure");
            return page(uri, "text/html", "<div class=result><a class=result__a href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdoc'>文档</a><div class=result__snippet>摘要</div></div>");
        }).combine(ToolRegistry.empty());
        var result = JSON.readTree(registry.find("web_search").orElseThrow().execute("{\"query\":\"Java\"}"));
        assertEquals("duckduckgo", result.path("source").asText());
        assertEquals("https://example.com/doc", result.path("results").get(0).path("url").asText());
    }

    @Test void readsOnlyArticleTextWithBoundedPagingAndNoScript() throws Exception {
        var config = new HarnessConfig.Web(); config.maxPageChars = 500;
        AtomicInteger calls = new AtomicInteger();
        var registry = new WebTools(config, (uri, trusted) -> {
            assertNull(trusted); calls.incrementAndGet();
            return page(uri, "text/html; charset=utf-8", "<html><head><meta charset=utf-8><title>示例</title></head><body><nav>隐藏导航</nav><article><h1>正文</h1><p>" + "中文内容".repeat(300) + "</p><script>private-script</script></article></body></html>");
        }).combine(ToolRegistry.empty());
        var tool = registry.find("web_fetch").orElseThrow();
        var result = JSON.readTree(tool.execute("{\"url\":\"https://example.com/article\"}"));
        assertEquals("示例", result.path("title").asText());
        assertEquals(500, result.path("content").asText().length());
        assertTrue(result.path("truncated").asBoolean());
        assertEquals(500, result.path("next_start").asInt());
        assertFalse(result.toString().contains("private-script"));
        assertFalse(result.toString().contains("隐藏导航"));
        tool.execute("{\"url\":\"https://example.com/article\",\"start\":500}");
        assertEquals(1, calls.get());
    }

    @Test void disabledWebDoesNotAdvertiseToolsOrMakeRequests() throws Exception {
        var config = new HarnessConfig.Web(); config.enabled = false;
        var tools = new WebTools(config, (uri, trusted) -> { throw new AssertionError(); }).combine(ToolRegistry.empty());
        assertTrue(tools.definitions().isEmpty());
        config.provider = "unknown";
        assertThrows(java.io.IOException.class, () -> WebTools.validate(config));
    }

    @Test void blocksLocalPrivateAndCredentialUrls() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1"))
            assertFalse(PublicWebClient.publicAddress(InetAddress.getByName(ip)), ip);
        assertTrue(PublicWebClient.publicAddress(InetAddress.getByName("8.8.8.8")));
        for (String url : List.of("file:///etc/passwd", "http://user:password@example.com/", "ftp://example.com/"))
            assertThrows(java.io.IOException.class, () -> PublicWebClient.validateUri(URI.create(url)));
        assertThrows(java.io.IOException.class, () -> new PublicWebClient(2).get(URI.create("http://127.0.0.1/"), null));
    }

    @Test void customSearchTransportHasNoCredentialsAndChecksRedirectsAndLimits() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = "{\"results\":[{\"title\":\"Local search\",\"url\":\"https://example.com/docs\",\"content\":\"snippet\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/large", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 2_000_001);
            try { exchange.getResponseBody().write(new byte[2_000_001]); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var config = new HarnessConfig.Web(); config.provider = "searxng"; config.searchUrl = endpoint + "/search";
            var registry = new WebTools(config).combine(ToolRegistry.empty());
            assertTrue(registry.find("web_search").orElseThrow().execute("{\"query\":\"Java\"}").contains("https://example.com/docs"));
            assertEquals(1, calls.get());
            var client = new PublicWebClient(2);
            assertThrows(java.io.IOException.class, () -> client.get(URI.create(endpoint + "/redirect"), URI.create(endpoint)));
            assertThrows(java.io.IOException.class, () -> client.get(URI.create(endpoint + "/large"), URI.create(endpoint)));
        } finally { server.stop(0); }
    }
}
