package com.erkasa.backend.features.promptvideo.script;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderGateway;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.LlmResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a video idea into a {@link VideoScript}.
 *
 * 1. Ask the LLM for JSON that follows {@link ScriptSchema}.
 * 2. Check it with {@link ScriptValidator}.
 * 3. If something is wrong, ask once more and say exactly what to fix.
 */
@Service
public class ScriptGenerator {

    private static final Logger log = LoggerFactory.getLogger(ScriptGenerator.class);
    private static final int MAX_ATTEMPTS = 2;

    /**
     * Answer budget in tokens. Structured outputs make every scene carry every visual field
     * (about 20, most of them empty), so a 60-90 s script of 10-15 scenes is 4,000-7,000 tokens,
     * more in Albanian. Only the tokens actually written are paid, so a high limit costs nothing.
     */
    static final int MAX_TOKENS = 16_000;
    /** If an answer is still cut off, the retry gets this much (and is asked to write less). */
    static final int MAX_TOKENS_RETRY = 32_000;
    static final String TRUNCATED = "TRUNCATED";

    private final ProviderGateway gateway;
    private final ObjectMapper json;

    public ScriptGenerator(ProviderGateway gateway, ObjectMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    public VideoScript generate(ScriptRequest request, CallContext ctx) {
        List<String> problems = List.of();
        int maxTokens = MAX_TOKENS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmRequest llmRequest = new LlmRequest(
                    ScriptPrompts.system(request),
                    ScriptPrompts.user(request, problems),
                    maxTokens,
                    ScriptSchema.schema(request.template()));
            LlmResponse response;
            try {
                response = gateway.generateText(llmRequest, ctx);
            } catch (ProviderException e) {
                // The answer did not fit: try once more with a bigger budget and ask for shorter texts.
                if (!TRUNCATED.equals(e.getCode()) || maxTokens >= MAX_TOKENS_RETRY || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Script attempt {}: answer cut off at {} tokens, retrying with {}", attempt, maxTokens, MAX_TOKENS_RETRY);
                maxTokens = MAX_TOKENS_RETRY;
                problems = List.of("Your previous answer was too long and was cut off. Keep every text short "
                        + "and use the lower end of the allowed number of scenes.");
                continue;
            }

            VideoScript script;
            try {
                script = json.readValue(response.text(), VideoScript.class);
            } catch (JacksonException e) {
                problems = List.of("The answer was not valid JSON for the schema.");
                log.warn("Script attempt {}: invalid JSON", attempt);
                continue;
            }

            problems = ScriptValidator.validate(script, request.durationSeconds(), request.template());
            if (problems.isEmpty()) {
                log.info("Script ready: {} scenes, {} words, template {} (attempt {})",
                        script.scenes().size(), script.narrationWords(), request.template(), attempt);
                return script;
            }
            log.warn("Script attempt {} had problems: {}", attempt, problems);
        }

        throw new ProviderException("script", "INVALID_SCRIPT",
                "Could not get a valid script: " + String.join(" ", problems), false, null);
    }
}