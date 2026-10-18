package com.erkasa.backend.features.promptvideo.script;

import java.util.List;

import com.erkasa.backend.providers.AspectRatio;

/**
 * The instructions sent to the LLM. Kept in one place so they are easy to read and improve:
 * most of the script quality comes from this text.
 */
public final class ScriptPrompts {

    private ScriptPrompts() {
    }

    public static String system(ScriptRequest request) {
        int seconds = request.durationSeconds();
        int minScenes = Math.max(ScriptValidator.MIN_SCENES, Math.round(seconds / 9f));
        int maxScenes = Math.min(ScriptValidator.MAX_SCENES, Math.max(minScenes + 1, Math.round(seconds / 5f)));
        String language = request.language().displayName();
        String framing = request.aspectRatio() == AspectRatio.LANDSCAPE
                ? "Horizontal 16:9 composition."
                : request.aspectRatio() == AspectRatio.SQUARE
                  ? "Square 1:1 composition."
                  : "Vertical 9:16 composition with the main subject in the center.";

        return """
                You write scripts for short social media videos (Reels, TikTok, YouTube Shorts).

                Rules:
                - Write "narration" and "onScreenText" in %s. Write "imagePrompt" in English.
                - The whole narration must take about %d seconds when read aloud at a natural pace: \
                about %d words in total, never more than %d.
                - Use %d to %d scenes, one idea per scene.
                - The first sentence of scene 1 must hook the viewer. The last scene ends with a clear closing line.
                - "onScreenText": at most 6 words with the key idea of the scene. It may be an empty string.
                - "imagePrompt": a detailed description for an image model: subject, setting, lighting, \
                camera angle and style. %s No text, letters, logos or watermarks in the image. \
                Keep one consistent visual style across all scenes. Do not depict real, identifiable \
                people or copyrighted characters.
                - "musicMood": the background music that fits the video best.
                - Be factually accurate. If you are not sure about a fact, keep the statement general.
                """.formatted(language, seconds, ScriptValidator.targetWords(seconds),
                ScriptValidator.maxWords(seconds), minScenes, maxScenes, framing);
    }

    public static String user(ScriptRequest request, List<String> problemsToFix) {
        StringBuilder sb = new StringBuilder()
                .append("Video idea: ").append(request.idea().trim()).append('\n')
                .append("Target duration: ").append(request.durationSeconds()).append(" seconds\n")
                .append("Language: ").append(request.language().displayName()).append('\n');
        if (problemsToFix != null && !problemsToFix.isEmpty()) {
            sb.append("\nYour previous script had these problems. Write a new script that fixes them:\n");
            problemsToFix.forEach(p -> sb.append("- ").append(p).append('\n'));
        }
        return sb.toString();
    }
}