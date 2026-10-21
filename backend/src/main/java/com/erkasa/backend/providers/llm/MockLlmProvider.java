package com.erkasa.backend.providers.llm;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.erkasa.backend.providers.ProviderUsage;

/**
 * Free, offline stand-in for a real LLM. Active while erkasa.providers.llm=mock (the default).
 *
 * - Free text request: echoes the prompt.
 * - Request with a script schema (has "scenes"): returns a fake but VALID video script whose
 *   length fits the "Target duration" in the prompt, so the whole pipeline can run for free.
 *
 * Token counts are rough estimates (about 4 characters per token), cost is always 0.
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "llm", havingValue = "mock", matchIfMissing = true)
public class MockLlmProvider implements LlmProvider {

    private static final Pattern DURATION = Pattern.compile("Target duration: (\\d+) seconds");
    private static final Pattern IDEA = Pattern.compile("Video idea: (.+)");

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-llm";
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        String text = isScriptSchema(request.jsonSchema()) ? mockScript(prompt) : echo(prompt);

        long inputTokens = estimateTokens(request.systemPrompt()) + estimateTokens(prompt);
        return new LlmResponse(text, ProviderUsage.free(model(), "tokens", inputTokens, estimateTokens(text)));
    }

    private static String echo(String prompt) {
        String preview = prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt;
        return "MOCK RESPONSE for: " + preview;
    }

    @SuppressWarnings("unchecked")
    private static boolean isScriptSchema(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> props)) {
            return false;
        }
        return ((Map<String, Object>) props).containsKey("scenes");
    }

    /** About one scene per 8 seconds, with narration that takes about 90% of the target time. */
    private static String mockScript(String prompt) {
        int seconds = 45;
        Matcher d = DURATION.matcher(prompt);
        if (d.find()) {
            seconds = Integer.parseInt(d.group(1));
        }
        Matcher i = IDEA.matcher(prompt);
        String idea = i.find() ? i.group(1).trim() : "your idea";

        int scenes = Math.max(3, Math.min(12, Math.round(seconds / 8f)));
        int wordsPerScene = Math.max(4, Math.round(seconds * 2.5f * 0.9f / scenes));

        StringBuilder json = new StringBuilder()
                .append("{\"title\":\"").append(escape("MOCK: " + shorten(idea, 60))).append("\",")
                .append("\"musicMood\":\"inspiring\",\"scenes\":[");
        for (int n = 1; n <= scenes; n++) {
            if (n > 1) {
                json.append(',');
            }
            json.append("{\"narration\":\"").append(escape(narration(n, wordsPerScene))).append("\",")
                    .append("\"onScreenText\":\"Scene ").append(n).append("\",")
                    .append("\"imagePrompt\":\"").append(escape("Mock scene " + n + " illustrating: " + idea))
                    .append("\",")
                    // one sound effect in the first scene, like the real LLM may choose (5.6)
                    .append("\"sfx\":").append(n == 1 ? "[{\"sound\":\"ding\",\"onWord\":\"mock\"}]" : "[]")
                    .append("}");
        }
        return json.append("]}").toString();
    }

    private static String narration(int scene, int words) {
        StringBuilder sb = new StringBuilder("This is mock scene ").append(scene);
        int count = 4;
        while (count < words) {
            sb.append(" lorem");
            count++;
        }
        return sb.append('.').toString();
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}