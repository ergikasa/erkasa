package com.erkasa.backend.features.promptvideo.script;

import java.util.ArrayList;
import java.util.List;

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

    public static List<String> validate(VideoScript script, int targetSeconds) {
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
            if (isBlank(s.imagePrompt())) {
                problems.add("Scene " + n + " has no imagePrompt.");
            }
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