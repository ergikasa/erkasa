package com.erkasa.backend.features.promptvideo.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema the LLM must follow for a script.
 *
 * Anthropic structured outputs accept only part of JSON Schema: every object needs
 * "additionalProperties": false, and limits like minLength or "at least 3 items" are not
 * allowed. Those rules live in {@link ScriptValidator} instead.
 */
public final class ScriptSchema {

    public static final List<String> MUSIC_MOODS =
            List.of("calm", "upbeat", "dramatic", "inspiring", "mysterious", "fun");

    private ScriptSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> sceneProps = new LinkedHashMap<>();
        sceneProps.put("narration", Map.of("type", "string"));
        sceneProps.put("onScreenText", Map.of("type", "string"));
        sceneProps.put("imagePrompt", Map.of("type", "string"));

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("type", "object");
        scene.put("properties", sceneProps);
        scene.put("required", List.of("narration", "onScreenText", "imagePrompt"));
        scene.put("additionalProperties", false);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string"));
        props.put("musicMood", Map.of("type", "string", "enum", MUSIC_MOODS));
        props.put("scenes", Map.of("type", "array", "minItems", 1, "items", scene));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("title", "musicMood", "scenes"));
        root.put("additionalProperties", false);
        return root;
    }
}