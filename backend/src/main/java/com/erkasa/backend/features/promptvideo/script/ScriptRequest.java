package com.erkasa.backend.features.promptvideo.script;

import com.erkasa.backend.jobs.VideoOptions;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/**
 * What the user asks for.
 *
 * @param idea            the video idea in the user's own words
 * @param language        language of narration and subtitles
 * @param durationSeconds target length, 15 to 90 seconds
 * @param aspectRatio     PORTRAIT for Reels/TikTok, LANDSCAPE for YouTube
 * @param options         purpose, template, audience, tone... (9.2). The template here is the one
 *                        the scenes are written for (see SceneCatalog.effective).
 */
public record ScriptRequest(String idea, Language language, int durationSeconds, AspectRatio aspectRatio,
                            VideoOptions options) {

    public static final int MIN_DURATION = 15;
    public static final int MAX_DURATION = 90;

    public ScriptRequest {
        options = options == null ? VideoOptions.classic() : options;
    }

    /** A request with the classic options (how scripts were written before phase 9). */
    public ScriptRequest(String idea, Language language, int durationSeconds, AspectRatio aspectRatio) {
        this(idea, language, durationSeconds, aspectRatio, null);
    }

    public VideoOptions.Template template() {
        return options.template();
    }
}
