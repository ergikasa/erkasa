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
                     String handle) {

    public static final String IMAGE = "image";
    public static final Set<String> TYPES = Set.of("image", "title", "flow", "list", "stat", "compare", "code", "cta");

    /** The scene shows an AI image (also for old scripts written before visuals existed). */
    public static boolean isImage(Visual v) {
        return v == null || v.type() == null || IMAGE.equals(v.type());
    }

    public static Visual image() {
        return new Visual(IMAGE, "", "", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "", "");
    }
}
