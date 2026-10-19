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
                - "visual": what the scene SHOWS. Prefer motion graphics that explain; they are \
                drawn as animated diagrams, so they are clearer and cheaper than pictures. \
                Use "image" only when a real-world picture really helps (a place, an object, a mood). \
                For technical, educational or "how it works" ideas, most scenes should NOT be images. \
                Types and the fields they use (leave every other field empty: "", [] or 0):
                  * "title": title (max 5 words), subtitle. Good for the first scene.
                  * "flow": items = 2-6 steps of a process, 1-4 words each (e.g. Code, Build, Test, Deploy).
                  * "list": items = 2-5 points, max 8 words each.
                  * "stat": value (a number), prefix, suffix (e.g. "x", "%%"), label. Only for real, well-known numbers.
                  * "compare": leftTitle, left (1-4 rows), rightTitle, right (1-4 rows). Before/after, bad/good.
                  * "code": code (max 12 lines, max 48 characters per line), language (e.g. "deploy.yml").
                  * "cta": text (closing line), handle (short, may be empty). Good for the last scene.
                  * "image": uses "imagePrompt" only.
                Items appear on screen when the narration says them, so the narration must mention \
                every step / item / row, in the same order and with the same first word.
                Write visual texts in %s. For non-image scenes "imagePrompt" is an empty string.
                - "musicMood": the background music that fits the video best.
                - Be factually accurate. If you are not sure about a fact, keep the statement general.
                """.formatted(language, seconds, ScriptValidator.targetWords(seconds),
                ScriptValidator.maxWords(seconds), minScenes, maxScenes, framing, language);
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