package com.erkasa.backend.features.promptvideo.script;

import java.util.ArrayList;
import java.util.List;

import com.erkasa.backend.jobs.VideoOptions.Template;

/**
 * Checks the rules the JSON schema cannot express. Returns a list of problems in plain English;
 * empty = the script is fine. The problems are sent back to the LLM so it can fix them.
 */
public final class ScriptValidator {

    /** A natural speaking pace for narration, in words per second. */
    public static final double WORDS_PER_SECOND = 2.5;

    public static final int MIN_SCENES = 3;
    public static final int MAX_SCENES = 15;
    public static final int MAX_ON_SCREEN_WORDS = 8;

    private ScriptValidator() {
    }

    /** Checks a CLASSIC script (every scene type of the classic template is allowed). */
    public static List<String> validate(VideoScript script, int targetSeconds) {
        return validate(script, targetSeconds, Template.CLASSIC);
    }

    /** Checks a script written for one template: scenes may only use that template's types (9.2). */
    public static List<String> validate(VideoScript script, int targetSeconds, Template template) {
        List<String> problems = new ArrayList<>();
        if (script == null) {
            problems.add("The script is empty.");
            return problems;
        }
        if (isBlank(script.title())) {
            problems.add("The title is empty.");
        }
        if (script.musicMood() == null || !ScriptSchema.MUSIC_MOODS.contains(script.musicMood())) {
            problems.add("musicMood must be one of " + ScriptSchema.MUSIC_MOODS + ".");
        }

        List<VideoScript.Scene> scenes = script.scenes() == null ? List.of() : script.scenes();
        if (scenes.size() < MIN_SCENES || scenes.size() > MAX_SCENES) {
            problems.add("There are " + scenes.size() + " scenes; use between " + MIN_SCENES
                    + " and " + MAX_SCENES + ".");
        }
        for (int i = 0; i < scenes.size(); i++) {
            VideoScript.Scene s = scenes.get(i);
            int n = i + 1;
            if (isBlank(s.narration())) {
                problems.add("Scene " + n + " has no narration.");
            }
            if (s.needsImage() && isBlank(s.imagePrompt())) {
                problems.add("Scene " + n + " has no imagePrompt.");
            }
            String type = Visual.typeOf(s.visual());
            if (Visual.TYPES.contains(type) && !SceneCatalog.types(template).contains(type)) {
                problems.add("Scene " + n + " uses \"" + type + "\", which this video style does not have; use one of "
                        + SceneCatalog.types(template) + ".");
            }
            problems.addAll(VisualRules.check(s.visual(), n));
            problems.addAll(soundProblems(s, n));
            if (countWords(s.onScreenText()) > MAX_ON_SCREEN_WORDS) {
                problems.add("Scene " + n + " onScreenText is too long; use at most "
                        + MAX_ON_SCREEN_WORDS + " words.");
            }
        }

        double estimatedSeconds = script.narrationWords() / WORDS_PER_SECOND;
        if (estimatedSeconds > targetSeconds * 1.2 + 3) {
            problems.add("The narration is too long: about " + Math.round(estimatedSeconds)
                    + " seconds for a " + targetSeconds + "-second video. Use at most "
                    + maxWords(targetSeconds) + " words in total.");
        } else if (estimatedSeconds < targetSeconds * 0.4) {
            problems.add("The narration is too short: about " + Math.round(estimatedSeconds)
                    + " seconds for a " + targetSeconds + "-second video. Use about "
                    + targetWords(targetSeconds) + " words in total.");
        }
        return problems;
    }

    /**
     * Sound effects: known keys and at most MAX_PER_SCENE. An onWord that is not in the
     * narration is not an error (no paid retry for that): the worker then plays it at the start.
     */
    static List<String> soundProblems(VideoScript.Scene s, int n) {
        List<String> problems = new java.util.ArrayList<>();
        if (s.sfx().size() > SoundEffects.MAX_PER_SCENE) {
            problems.add("Scene " + n + " has " + s.sfx().size() + " sound effects; use at most "
                    + SoundEffects.MAX_PER_SCENE + ".");
        }
        for (SoundCue cue : s.sfx()) {
            if (!SoundEffects.CATALOG.containsKey(cue.sound())) {
                problems.add("Scene " + n + " uses an unknown sound effect \"" + cue.sound() + "\"; use one of "
                        + SoundEffects.SOUNDS + ".");
            }
        }
        return problems;
    }

    /** Words for the narration to last about targetSeconds (a little under, so it never runs over). */
    public static int targetWords(int targetSeconds) {
        return (int) Math.round(targetSeconds * WORDS_PER_SECOND * 0.9);
    }

    public static int maxWords(int targetSeconds) {
        return (int) Math.round(targetSeconds * WORDS_PER_SECOND * 1.1);
    }

    public static int countWords(String text) {
        if (isBlank(text)) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}