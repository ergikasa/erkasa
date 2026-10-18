package com.erkasa.backend.providers.llm;


import com.erkasa.backend.providers.ProviderUsage;

/** The generated text and what it cost. */
public record LlmResponse(String text, ProviderUsage usage) {
}