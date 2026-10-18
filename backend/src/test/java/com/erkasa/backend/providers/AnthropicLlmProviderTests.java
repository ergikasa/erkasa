package com.erkasa.backend.providers;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.llm.AnthropicLlmProvider;
import com.erkasa.backend.providers.llm.AnthropicProperties;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.LlmResponse;
import com.sun.net.httpserver.HttpServer;

/**
 * The real AnthropicLlmProvider against a FAKE Anthropic server running inside the test.
 * No API key, no internet, no cost: we check the request we send and how we read the answers.
 */
class AnthropicLlmProviderTests {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastVersion = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseJson = "{}";

    @BeforeEach
    void startFakeAnthropic() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            lastVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicLlmProvider provider() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        AnthropicProperties props = new AnthropicProperties("test-key", "claude-sonnet-5-5", url,
                new BigDecimal("2.00"), new BigDecimal("10.00"), Duration.ofSeconds(5));
        return new AnthropicLlmProvider(props, new ProviderHttp());
    }

    private static String answer(String text, String stopReason) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[{"type":"text","text":%s}],
                 "stop_reason":"%s","usage":{"input_tokens":1000,"output_tokens":500}}
                """.formatted(jsonString(text), stopReason);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void sendsTheRightRequestAndReadsTextUsageAndCost() {
        responseJson = answer("Hello from Claude", "end_turn");

        LlmResponse response = provider().generate(new LlmRequest("Be brief.", "Say hello", 200));

        assertThat(lastApiKey.get()).isEqualTo("test-key");
        assertThat(lastVersion.get()).isEqualTo("2023-06-01");
        assertThat(lastBody.get())
                .contains("\"model\":\"claude-sonnet-5-5\"", "\"max_tokens\":200", "\"system\":\"Be brief.\"",
                        "\"role\":\"user\"", "\"content\":\"Say hello\"")
                .doesNotContain("output_config");

        assertThat(response.text()).isEqualTo("Hello from Claude");
        assertThat(response.usage().inputUnits()).isEqualTo(1000);
        assertThat(response.usage().outputUnits()).isEqualTo(500);
        // 1000 x $2/M + 500 x $10/M = $0.002 + $0.005
        assertThat(response.usage().costUsd()).isEqualByComparingTo("0.007");
    }

    @Test
    void schemaIsSentAsStructuredOutput() {
        responseJson = answer("{\"ok\":true}", "end_turn");
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("ok", Map.of("type", "boolean")));

        provider().generate(new LlmRequest("sys", "give json", 100, schema));

        assertThat(lastBody.get()).contains("\"output_config\"", "\"json_schema\"", "\"additionalProperties\":false");
    }

    @Test
    void overloadedServerIsRetryable() {
        status = 529;
        responseJson = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}";

        assertThatThrownBy(() -> provider().generate(new LlmRequest("s", "u", 10)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getCode()).isEqualTo("PROVIDER_ERROR");
                });
    }

    @Test
    void badApiKeyIsNotRetryable() {
        status = 401;
        responseJson = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}";

        assertThatThrownBy(() -> provider().generate(new LlmRequest("s", "u", 10)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getCode()).isEqualTo("AUTH_FAILED");
                });
    }

    @Test
    void refusalIsReported() {
        responseJson = answer("I can't help with that.", "refusal");

        assertThatThrownBy(() -> provider().generate(new LlmRequest("s", "u", 10)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.getCode()).isEqualTo("REFUSED"));
    }

    @Test
    void missingApiKeyFailsAtStartup() {
        AnthropicProperties noKey = new AnthropicProperties("", null, null, null, null, null);

        assertThatThrownBy(() -> new AnthropicLlmProvider(noKey, new ProviderHttp()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}