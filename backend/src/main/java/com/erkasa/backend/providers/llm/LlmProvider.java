package com.erkasa.backend.providers.llm;



/** Generates text (the video script). Implementations: MockLlmProvider now, Anthropic in 3.2. */
public interface LlmProvider {

    /** Short id stored in provider_usage, e.g. "mock" or "anthropic". */
    String name();

    /** Default model, used in provider_usage when a call fails before the provider reports one. */
    String model();

    LlmResponse generate(LlmRequest request);
}