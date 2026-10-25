package com.erkasa.backend.features.promptvideo.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.erkasa.backend.jobs.VideoOptions.Template;

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

    /** The schema for CLASSIC videos (as before phase 9). */
    public static Map<String, Object> schema() {
        return schema(Template.CLASSIC);
    }

    /** The schema for one template: "visual.type" may only be one of that template's scenes. */
    public static Map<String, Object> schema(Template template) {
        Map<String, Object> sceneProps = new LinkedHashMap<>();
        sceneProps.put("narration", Map.of("type", "string"));
        sceneProps.put("onScreenText", Map.of("type", "string"));
        sceneProps.put("imagePrompt", Map.of("type", "string"));
        sceneProps.put("visual", visual(template));
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

    /** One message of an exchange: {from, to, label} (see {@link Message}). */
    private static Map<String, Object> message() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", Map.of("type", "integer"));
        props.put("to", Map.of("type", "integer"));
        props.put("label", Map.of("type", "string"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", List.of("from", "to", "label"));
        m.put("additionalProperties", false);
        return m;
    }

    /** The flat visual object (see {@link Visual}): every field present, unused ones empty. */
    private static Map<String, Object> visual(Template template) {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> strings = Map.of("type", "array", "items", str);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string", "enum", SceneCatalog.types(template)));
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
        // 9.2: fields of the INFOGRAPHIC and ANIMATED_2D scenes (empty for the others)
        props.put("values", Map.of("type", "array", "items", Map.of("type", "number")));
        props.put("labels", strings);
        props.put("icons", strings);
        props.put("pose", str);
        // 9.4b: exchange scenes - who sends what to whom
        props.put("messages", Map.of("type", "array", "items", message()));

        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("type", "object");
        visual.put("properties", props);
        visual.put("required", List.copyOf(props.keySet()));
        visual.put("additionalProperties", false);
        return visual;
    }
}