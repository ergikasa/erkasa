package com.erkasa.backend.features.promptvideo.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sound effect library (5.6). The render worker has one folder per key
 * (render-worker/sfx/&lt;key&gt;/); the LLM may only choose these keys.
 *
 * Whooshes between scenes and pops when list / flow / compare items appear are added by the
 * worker automatically; the LLM only adds accents that fit what the narration says.
 */
public final class SoundEffects {

    /** At most this many LLM sounds per scene, so the voice is never buried. */
    public static final int MAX_PER_SCENE = 2;

    /** key -> when to use it (also sent to the LLM). Keep in sync with render-worker/src/sfx.ts. */
    public static final Map<String, String> CATALOG = catalog();

    public static final List<String> SOUNDS = List.copyOf(CATALOG.keySet());

    private SoundEffects() {
    }

    private static Map<String, String> catalog() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("whoosh", "fast movement, something flies in");
        m.put("swoosh", "short quick movement");
        m.put("riser", "tension building before a reveal");
        m.put("impact", "big reveal, strong statement, a title landing");
        m.put("pop", "something small appears");
        m.put("click", "a button, a choice, a switch");
        m.put("tick", "time passing, a clock, a countdown");
        m.put("ding", "a fact, a correct answer, an idea");
        m.put("chime", "a pleasant reveal, magic, an insight");
        m.put("notification", "a message, an alert, an app");
        m.put("success", "done, it works, a goal reached");
        m.put("error", "a mistake, a failure, something wrong");
        m.put("typing", "writing, code, a keyboard");
        m.put("beep", "a machine, a scan, a robot");
        m.put("glitch", "a bug, hacking, something broken digitally");
        m.put("cash", "money earned, a sale, a price");
        m.put("coin", "a small gain, points, a reward");
        m.put("camera", "a photo, a screenshot, a snapshot");
        m.put("heartbeat", "suspense, health, something alive");
        m.put("boing", "something funny or surprising");
        return java.util.Collections.unmodifiableMap(m);
    }
}
