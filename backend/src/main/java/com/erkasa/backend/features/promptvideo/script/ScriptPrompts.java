package com.erkasa.backend.features.promptvideo.script;

import java.util.List;

import com.erkasa.backend.jobs.VideoOptions;
import com.erkasa.backend.jobs.VideoOptions.Audience;
import com.erkasa.backend.jobs.VideoOptions.Purpose;
import com.erkasa.backend.jobs.VideoOptions.Template;
import com.erkasa.backend.jobs.VideoOptions.Tone;
import com.erkasa.backend.providers.AspectRatio;

/**
 * The instructions sent to the LLM. Kept in one place so they are easy to read and improve:
 * most of the script quality comes from this text.
 *
 * Since 9.2 the prompt is built from the wizard choices: the purpose decides the structure,
 * the audience how much to explain, the tone the voice, and the template which scenes exist.
 */
public final class ScriptPrompts {

    private ScriptPrompts() {
    }

    public static String system(ScriptRequest request) {
        VideoOptions o = request.options();
        Template template = o.template();
        int seconds = request.durationSeconds();
        int minScenes = Math.max(ScriptValidator.MIN_SCENES, Math.round(seconds / 9f));
        int maxScenes = Math.min(ScriptValidator.MAX_SCENES, Math.max(minScenes + 1, Math.round(seconds / 5f)));
        String language = request.language().displayName();
        boolean images = SceneCatalog.types(template).contains(Visual.IMAGE);

        StringBuilder sb = new StringBuilder()
                .append(purpose(o.purpose())).append("\n\n")
                .append("Structure:\n").append(structure(o.purpose())).append('\n')
                .append("Audience: ").append(audience(o.audience())).append('\n')
                .append("Tone: ").append(tone(o.tone())).append("\n\n")
                .append("Visual style: ").append(style(template)).append("\n\n");

        sb.append("""
                Rules:
                - Write "narration" and "onScreenText" in %s.%s
                - The whole narration must take about %d seconds when read aloud at a natural pace: \
                about %d words in total, never more than %d.
                - Use %d to %d scenes, one idea per scene.
                - The first sentence of scene 1 must hook the viewer. The last scene ends with a clear closing line.
                - "onScreenText": at most 6 words with the key idea of the scene. It may be an empty string.
                """.formatted(language, images ? " Write \"imagePrompt\" in English." : "", seconds,
                ScriptValidator.targetWords(seconds), ScriptValidator.maxWords(seconds), minScenes, maxScenes));

        if (images) {
            sb.append("""
                    - "imagePrompt": a detailed description for an image model: subject, setting, lighting, \
                    camera angle and style. %s No text, letters, logos or watermarks in the image. \
                    Keep one consistent visual style across all scenes. Do not depict real, identifiable \
                    people or copyrighted characters.
                    - "visual": what the scene SHOWS. Prefer motion graphics that explain; they are \
                    drawn as animated diagrams, so they are clearer and cheaper than pictures. \
                    For technical, educational or "how it works" ideas, most scenes should NOT be images.
                    """.formatted(framing(request.aspectRatio())));
        } else {
            sb.append("""
                    - "visual": what the scene SHOWS. Every scene is drawn from a fixed library of animated \
                    scenes, icons and characters; there are no photos or AI images. "imagePrompt" is always "". \
                    Vary the scene types: never use the same type more than twice in a row.
                    """);
        }
        sb.append("  Types and the fields they use (leave every other field empty: \"\", [] or 0):\n")
                .append(SceneCatalog.guide(template));
        if (SceneCatalog.usesIcons(template)) {
            sb.append("  Icons: use ONLY these names, copied exactly (they stay in English): ")
                    .append(String.join(", ", SceneCatalog.ICONS)).append(".\n");
        }
        if (SceneCatalog.types(template).contains("character")) {
            sb.append("  Poses (copied exactly, in English): ").append(String.join(", ", SceneCatalog.POSES)).append(".\n");
        }

        sb.append("""
                Items appear on screen when the narration says them, so the narration must mention \
                every step / item / row, in the same order and with the same first word.
                Write visual texts in %s.
                - "musicMood": the background music that fits the video and its tone best.
                - "sfx": 0 to 2 sound effects per scene that underline what the narration says. \
                "onWord" is ONE word copied from that scene's narration where the sound plays \
                ("" = when the scene starts). Many scenes need none; never decorate every sentence. \
                Whooshes between scenes and pops when items appear are added automatically, so do not add those. \
                Sounds: %s
                - Be factually accurate. If you are not sure about a fact, keep the statement general. \
                Never invent statistics, prices, dates or quotes.
                """.formatted(language, soundList()));
        return sb.toString();
    }

    static String purpose(Purpose p) {
        return switch (p) {
            case EXPLAINER -> "You write scripts for animated explainer videos that teach ONE topic so clearly "
                    + "that the viewer could explain it to a friend afterwards.";
            case SHORT_FORM -> "You write scripts for short social media videos (Reels, TikTok, YouTube Shorts) "
                    + "that stop the scroll in the first second and keep people watching to the end.";
            case BUSINESS -> "You write scripts for clear infographic videos for businesses: presentations, "
                    + "clients, reports and social media posts of a company.";
        };
    }

    static String structure(Purpose p) {
        return switch (p) {
            case EXPLAINER -> """
                    1. Hook: a question or a surprising fact about the topic.
                    2. Why it matters to the viewer, in one scene.
                    3. The explanation, step by step, one step per scene, with a concrete example.
                    4. A short recap of the key idea.
                    5. A closing line (and a call to action only if it fits).
                    """;
            case SHORT_FORM -> """
                    1. Hook in the first 2 seconds: a bold claim, a question or a "you're doing it wrong".
                    2. Fast pace: short sentences, one idea per scene, no long introductions.
                    3. A payoff or twist near the end that rewards watching.
                    4. A punchy closing line that makes people comment, share or watch again.
                    """;
            case BUSINESS -> """
                    1. The context or the problem, in one scene.
                    2. The key facts: numbers, steps or a timeline, one per scene. Use numbers only \
                    when they are in the idea or are well known; never invent company data.
                    3. What it means: the result or the benefit.
                    4. A clear takeaway or next step.
                    """;
        };
    }

    static String audience(Audience a) {
        return switch (a) {
            case BEGINNER -> "beginners. No jargon: explain every technical word in plain language the first "
                    + "time, and use everyday comparisons.";
            case INTERMEDIATE -> "people who know the basics. Use the correct terms with a short reminder of "
                    + "what they mean; do not explain the very basics.";
            case EXPERT -> "experts. Use precise terminology, skip the basics, and include one nuance, "
                    + "trade-off or common mistake that experts care about.";
        };
    }

    static String tone(Tone t) {
        return switch (t) {
            case EDUCATIONAL -> "clear, patient and friendly, like a good teacher.";
            case ENTERTAINING -> "playful and energetic, with surprise and light humor; still accurate.";
            case PROFESSIONAL -> "confident, concise and neutral. No slang, no jokes, no exclamation marks.";
        };
    }

    static String style(Template t) {
        return switch (t) {
            case CLASSIC -> "motion graphics (titles, steps, lists, numbers, comparisons, code) and AI images "
                    + "where a real picture helps.";
            case INFOGRAPHIC -> "animated infographic: charts, timelines, cycles, icon grids and numbers. "
                    + "Show structure and data, not decoration.";
            case ANIMATED_2D -> "flat 2D animated explainer: a simple character, icons joined by arrows, "
                    + "metaphors and before/after scenes. Make abstract ideas visible with simple objects.";
        };
    }

    private static String framing(AspectRatio ratio) {
        return ratio == AspectRatio.LANDSCAPE
                ? "Horizontal 16:9 composition."
                : ratio == AspectRatio.SQUARE
                  ? "Square 1:1 composition."
                  : "Vertical 9:16 composition with the main subject in the center.";
    }

    /** "ding (a fact, a correct answer, an idea); cash (money earned...); ..." */
    static String soundList() {
        StringBuilder sb = new StringBuilder();
        SoundEffects.CATALOG.forEach((key, use) -> sb.append(sb.isEmpty() ? "" : "; ").append(key)
                .append(" (").append(use).append(')'));
        return sb.toString();
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
