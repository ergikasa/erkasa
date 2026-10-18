package com.erkasa.backend.providers.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderHttp;
import com.erkasa.backend.providers.ProviderUsage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Claude through the Anthropic Messages API (POST /v1/messages).
 * Active when erkasa.providers.llm=anthropic.
 *
 * Docs: https://platform.claude.com/docs/en/api/messages
 * Structured outputs: https://platform.claude.com/docs/en/build-with-claude/structured-outputs
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "llm", havingValue = "anthropic")
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicLlmProvider implements LlmProvider {

    static final String NAME = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AnthropicProperties props;
    private final RestClient client;

    public AnthropicLlmProvider(AnthropicProperties props, ProviderHttp http) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "erkasa.providers.llm=anthropic but ANTHROPIC_API_KEY is not set (see infra/.env)");
        }
        this.props = props;
        this.client = http.client(props.baseUrl(), props.timeout());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        MessagesResponse response = send(body(request));

        String text = response.content() == null ? null : response.content().stream()
                .filter(b -> "text".equals(b.type()))
                .map(Block::text)
                .findFirst()
                .orElse(null);

        if ("refusal".equals(response.stop_reason())) {
            throw new ProviderException(NAME, "REFUSED", "Claude declined this request", false, null);
        }
        if ("max_tokens".equals(response.stop_reason()) && request.jsonSchema() != null) {
            throw new ProviderException(NAME, "TRUNCATED", "Answer cut off at max_tokens", false, null);
        }
        if (text == null) {
            throw new ProviderException(NAME, "EMPTY_RESPONSE", "No text in the answer", true, null);
        }

        long in = response.usage() == null ? 0 : response.usage().input_tokens();
        long out = response.usage() == null ? 0 : response.usage().output_tokens();
        String model = response.model() == null ? props.model() : response.model();
        return new LlmResponse(text, new ProviderUsage(model, "tokens", in, out, cost(in, out)));
    }

    private Map<String, Object> body(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("max_tokens", request.maxTokens());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system", request.systemPrompt());
        }
        body.put("messages", List.of(Map.of("role", "user", "content", request.userPrompt())));
        if (request.jsonSchema() != null) {
            // Forces the answer to be JSON matching the schema.
            body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", request.jsonSchema())));
        }
        return body;
    }

    private MessagesResponse send(Map<String, Object> body) {
        try {
            return client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", API_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errorBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw ProviderException.fromHttpStatus(NAME, res.getStatusCode().value(), errorBody);
                    })
                    .body(MessagesResponse.class);
        } catch (ResourceAccessException e) {
            // Timeout or network problem: worth a retry.
            throw new ProviderException(NAME, "TIMEOUT", e.getMessage(), true, e);
        }
    }

    private BigDecimal cost(long inputTokens, long outputTokens) {
        BigDecimal in = props.inputUsdPerMillion().multiply(BigDecimal.valueOf(inputTokens));
        BigDecimal out = props.outputUsdPerMillion().multiply(BigDecimal.valueOf(outputTokens));
        return in.add(out).divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    // ---------- the parts of the API response we read (names match the JSON; other fields are ignored) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagesResponse(String model, List<Block> content, String stop_reason, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(long input_tokens, long output_tokens) {
    }
}