package com.erkasa.backend.providers.llm;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers.anthropic" in application.yml.
 *
 * Prices are in US dollars per 1 million tokens. Update them here when Anthropic changes
 * prices or when you switch model: https://platform.claude.com/docs/en/about-claude/pricing
 *
 * @param apiKey               from ANTHROPIC_API_KEY (infra/.env locally, server variable in production)
 * @param model                e.g. claude-sonnet-5-5
 * @param baseUrl              https://api.anthropic.com (tests point it to a fake server)
 * @param inputUsdPerMillion   price of 1M input tokens for this model
 * @param outputUsdPerMillion  price of 1M output tokens for this model
 * @param timeout              how long to wait for an answer
 */
@ConfigurationProperties(prefix = "erkasa.providers.anthropic")
public record AnthropicProperties(String apiKey,
                                  String model,
                                  String baseUrl,
                                  BigDecimal inputUsdPerMillion,
                                  BigDecimal outputUsdPerMillion,
                                  Duration timeout) {

    public AnthropicProperties {
        model = model == null || model.isBlank() ? "claude-sonnet-5-5" : model;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl;
        inputUsdPerMillion = inputUsdPerMillion == null ? new BigDecimal("2.00") : inputUsdPerMillion;
        outputUsdPerMillion = outputUsdPerMillion == null ? new BigDecimal("10.00") : outputUsdPerMillion;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }
}