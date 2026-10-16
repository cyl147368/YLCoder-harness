package dev.codeharness.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class InlineCredentialTest {
    private static final String SECRET = "local-fixture-not-a-real-key";
    @TempDir Path workspace;

    @Test void inlineKeyAuthenticatesAndResumeDoesNotPersistCredential() throws Exception {
        List<String> authorization = new CopyOnWriteArrayList<>();
        List<String> payloads = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            payloads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path config = workspace.resolve("private.yaml");
            Files.writeString(config, "provider: openai-compatible\nmodel: fixture\nbase-url: http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1\napi-key: " + SECRET
                    + "\napi-key-env: HARNESS_INLINE_TEST_UNUSED\n");
            Result run = execute("--workspace", workspace.toString(), "--config", config.toString(), "--quiet", "--json", "run", "检查项目");
            assertEquals(0, run.code, run.err);
            String id = new ObjectMapper().readTree(run.out).path("session").asText();
            Result resumed = execute("--workspace", workspace.toString(), "--config", config.toString(), "--quiet", "--json", "resume", id, "继续检查");
            assertEquals(0, resumed.code, resumed.err);
            assertEquals(List.of("Bearer " + SECRET, "Bearer " + SECRET), authorization);
            String checkpoint = Files.readString(workspace.resolve(".harness/sessions/" + id + ".json"));
            assertFalse((run.out + run.err + resumed.out + resumed.err + checkpoint + payloads).contains(SECRET));
        } finally { server.stop(0); }
    }

    @Test void explicitVariableOverridesInlineAndEmptyInlineFallsBackToConfiguredOrNativeVariable() {
        HarnessConfig config = new HarnessConfig();
        config.apiKey = SECRET;
        config.apiKeyEnv = "CONFIGURED_KEY";
        Map<String, String> variables = Map.of("OVERRIDE_KEY", "override-value", "CONFIGURED_KEY", "configured-value",
                "OPENAI_API_KEY", "native-value", "ANTHROPIC_API_KEY", "anthropic-value");
        assertEquals(SECRET, HarnessCli.resolveApiKey(config, null, variables::get));
        assertEquals("override-value", HarnessCli.resolveApiKey(config, "OVERRIDE_KEY", variables::get));
        assertNull(HarnessCli.resolveApiKey(config, "MISSING_KEY", variables::get), "显式变量缺失时不得偷偷回退到配置密钥");
        assertNull(HarnessCli.resolveApiKey(config, "", variables::get));
        for (String blank : new String[]{null, "", "  "}) {
            config.apiKey = blank;
            assertEquals("configured-value", HarnessCli.resolveApiKey(config, null, variables::get));
        }
        config.apiKeyEnv = null;
        assertEquals("native-value", HarnessCli.resolveApiKey(config, null, variables::get));
        config.provider = "anthropic";
        assertEquals("anthropic-value", HarnessCli.resolveApiKey(config, null, variables::get));
        config.provider = "ollama";
        assertNull(HarnessCli.resolveApiKey(config, null, variables::get));
    }

    private static Result execute(String... arguments) {
        var command = HarnessCli.commandLine();
        StringWriter out = new StringWriter(), err = new StringWriter();
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return new Result(command.execute(arguments), out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
