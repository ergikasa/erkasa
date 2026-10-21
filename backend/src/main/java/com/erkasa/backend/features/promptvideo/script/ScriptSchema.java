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
        sceneProps.put("visual", visual());
        sceneProps.put("sfx", sfx());

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("type", "object");
        scene.put("properties", sceneProps);
        scene.put("required", List.of("narration", "onScreenText", "imagePrompt", "visual", "sfx"));
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

    /** 0-2 sound effects per scene (the limit is checked by ScriptValidator). */
    private static Map<String, Object> sfx() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sound", Map.of("type", "string", "enum", SoundEffects.SOUNDS));
        props.put("onWord", Map.of("type", "string"));
        Map<String, Object> cue = new LinkedHashMap<>();
        cue.put("type", "object");
        cue.put("properties", props);
        cue.put("required", List.of("sound", "onWord"));
        cue.put("additionalProperties", false);
        return Map.of("type", "array", "items", cue);
    }

    /** The flat visual object (see {@link Visual}): every field present, unused ones empty. */
    private static Map<String, Object> visual() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> strings = Map.of("type", "array", "items", str);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string", "enum",
                List.of("image", "title", "flow", "list", "stat", "compare", "code", "cta")));
        props.put("title", str);
        props.put("subtitle", str);
        props.put("items", strings);
        props.put("value", Map.of("type", "number"));
        props.put("prefix", str);
        props.put("suffix", str);
        props.put("label", str);
        props.put("leftTitle", str);
        props.put("rightTitle", str);
        props.put("left", strings);
        props.put("right", strings);
        props.put("code", str);
        props.put("language", str);
        props.put("text", str);
        props.put("handle", str);

        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("type", "object");
        visual.put("properties", props);
        visual.put("required", List.copyOf(props.keySet()));
        visual.put("additionalProperties", false);
        return visual;
    }
}