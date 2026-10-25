package com.erkasa.backend.features.promptvideo.script;

import java.util.List;
import java.util.Set;

/**
 * What a scene SHOWS. The LLM picks one type per scene: explain with motion graphics,
 * and use an AI image only when a real picture helps. The render worker draws the graphics
 * (render-worker/src/Graphics.tsx), so only "image" scenes cost an image-model call.
 *
 * One flat object for every type, because Anthropic structured outputs need a fixed shape:
 * fields a type does not use are empty ("" / [] / 0).
 *
 * @param type       image | title | flow | list | stat | compare | code | cta
 * @param title      title: the big title; cta: unused
 * @param subtitle   title: the line under it
 * @param items      flow: the steps (2-6, short); list: the points (2-5)
 * @param value      stat: the number that counts up, e.g. 200
 * @param prefix     stat: before the number, e.g. "$"
 * @param suffix     stat: after the number, e.g. "x" or "%"
 * @param label      stat: what the number means
 * @param leftTitle  compare: e.g. "Without CI/CD"
 * @param rightTitle compare: e.g. "With CI/CD"
 * @param left       compare: rows of the left side
 * @param right      compare: rows of the right side
 * @param code       code: the snippet, lines separated by \n
 * @param language   code: shown in the window title, e.g. "deploy.yml"
 * @param text       cta: the closing line
 * @param handle     cta: the button text, e.g. "@erkasa"
 * @param values     bars: one number per item (9.2)
 * @param labels     timeline: one date / step label per item (9.2)
 * @param icons      Lucide icon names from SceneCatalog.ICONS: iconGrid, iconLink (one per item),
 *                   metaphor, beforeAfter (two), zoom (one), character (0-2) (9.2)
 * @param pose       character: one of SceneCatalog.POSES (9.2); exchange / browser: the host beside
 *                   the diagram, or "" for none (9.4b)
 * @param messages   exchange: what the parties send each other, in order (9.4b)
 *
 * The types and their fields per template are listed in {@link SceneCatalog}. Scripts written
 * before 9.2 have no values / labels / icons / pose: they are null and read as empty.
 */
public record Visual(String type,
                     String title,
                     String subtitle,
                     List<String> items,
                     Double value,
                     String prefix,
                     String suffix,
                     String label,
                     String leftTitle,
                     String rightTitle,
                     List<String> left,
                     List<String> right,
                     String code,
                     String language,
                     String text,
                     String handle,
                     List<Double> values,
                     List<String> labels,
                     List<String> icons,
                     String pose,
                     List<Message> messages) {

    public static final String IMAGE = "image";
    /** Every type of every template (which template may use which: SceneCatalog). */
    public static final Set<String> TYPES = Set.of("image", "title", "flow", "list", "stat", "compare", "code", "cta",
            "bars", "timeline", "cycle", "iconGrid",
            "character", "iconLink", "metaphor", "beforeAfter", "zoom",
            "exchange", "browser");

    /** A visual without messages (the shape before 9.4b). */
    public Visual(String type, String title, String subtitle, List<String> items, Double value, String prefix,
                  String suffix, String label, String leftTitle, String rightTitle, List<String> left,
                  List<String> right, String code, String language, String text, String handle,
                  List<Double> values, List<String> labels, List<String> icons, String pose) {
        this(type, title, subtitle, items, value, prefix, suffix, label, leftTitle, rightTitle, left, right,
                code, language, text, handle, values, labels, icons, pose, List.of());
    }

    /** A visual without the 9.2 fields (the shape of every visual before templates). */
    public Visual(String type, String title, String subtitle, List<String> items, Double value, String prefix,
                  String suffix, String label, String leftTitle, String rightTitle, List<String> left,
                  List<String> right, String code, String language, String text, String handle) {
        this(type, title, subtitle, items, value, prefix, suffix, label, leftTitle, rightTitle, left, right,
                code, language, text, handle, List.of(), List.of(), List.of(), "", List.of());
    }

    /** The visual type, "image" for scripts written before visuals existed. */
    public static String typeOf(Visual v) {
        return v == null || v.type() == null ? IMAGE : v.type();
    }

    /** The scene shows an AI image (also for old scripts written before visuals existed). */
    public static boolean isImage(Visual v) {
        return v == null || v.type() == null || IMAGE.equals(v.type());
    }

    public static Visual image() {
        return new Visual(IMAGE, "", "", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "", "",
                List.of(), List.of(), List.of(), "", List.of());
    }
}
