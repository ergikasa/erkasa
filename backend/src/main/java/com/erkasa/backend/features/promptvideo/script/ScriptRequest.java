package com.erkasa.backend.features.promptvideo.script;

import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/**
 * What the user asks for.
 *
 * @param idea            the video idea in the user's own words
 * @param language        language of narration and subtitles
 * @param durationSeconds target length, 15 to 90 seconds
 * @param aspectRatio     PORTRAIT for Reels/TikTok, LANDSCAPE for YouTube
 */
public record ScriptRequest(String idea, Language language, int durationSeconds, AspectRatio aspectRatio) {

    public static final int MIN_DURATION = 15;
    public static final int MAX_DURATION = 90;
}