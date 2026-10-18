package com.erkasa.backend.providers.llm;

import java.util.Map;

/**
 * @param systemPrompt instructions for the model (role, rules, output format)
 * @param userPrompt   the actual request, e.g. the user's video idea
 * @param maxTokens    upper limit for the answer length
 * @param jsonSchema   optional JSON Schema. When set, the model MUST answer with JSON matching it
 *                     (Anthropic "structured outputs"). Null = free text.
 */
public record LlmRequest(String systemPrompt, String userPrompt, int maxTokens, Map<String, Object> jsonSchema) {

    /** Free-text request (no schema). */
    public LlmRequest(String systemPrompt, String userPrompt, int maxTokens) {
        this(systemPrompt, userPrompt, maxTokens, null);
    }
}