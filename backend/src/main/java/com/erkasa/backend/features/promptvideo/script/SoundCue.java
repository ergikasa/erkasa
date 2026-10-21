package com.erkasa.backend.features.promptvideo.script;

/**
 * One sound effect the LLM chose for a scene (5.6).
 *
 * @param sound  a key from {@link SoundEffects#SOUNDS}, e.g. "ding"
 * @param onWord the word of the narration where the sound plays; "" = when the scene starts
 */
public record SoundCue(String sound, String onWord) {

    public SoundCue {
        sound = sound == null ? "" : sound.strip().toLowerCase(java.util.Locale.ROOT);
        onWord = onWord == null ? "" : onWord.strip();
    }
}
