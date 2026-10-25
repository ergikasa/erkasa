package com.erkasa.backend.features.promptvideo.script;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.erkasa.backend.jobs.VideoOptions.Template;

/**
 * Which scenes each template may use, and what the LLM must fill for each (phase 9.2).
 *
 * The LLM never invents pictures for ANIMATED_2D and INFOGRAPHIC: it picks a scene type from
 * this catalog, icons from {@link #ICONS} and character poses from {@link #POSES}. The render
 * worker draws them from free CC0 libraries (Lucide icons, Open Peeps characters).
 *
 * A template goes into {@link #READY} only when the render worker can draw all its scenes
 * (INFOGRAPHIC in 9.3, ANIMATED_2D in 9.4). Until then, real videos fall back to CLASSIC
 * scenes, but keep the purpose, audience and tone (see {@link #effective}).
 */
public final class SceneCatalog {

    /** Templates the render worker can draw today (INFOGRAPHIC since 9.3, ANIMATED_2D since 9.4). */
    public static final Set<Template> READY = EnumSet.of(Template.CLASSIC, Template.INFOGRAPHIC, Template.ANIMATED_2D);

    /**
     * Lucide icon names (lucide.dev, lucide-react 1.61) the LLM may choose. Keep in sync with
     * render-worker/src/icons.ts (9.3); a name Lucide does not have breaks nothing but shows a plain circle.
     */
    public static final List<String> ICONS = List.of(
            // people and places
            "user", "users", "baby", "face-slightly-smiling", "face-slightly-frowning", "house", "building", "store", "factory", "school",
            "hospital", "map", "map-pin", "globe",
            // work and money
            "briefcase", "handshake", "dollar-sign", "euro", "wallet", "credit-card", "piggy-bank", "coins",
            "receipt", "shopping-cart", "package", "truck", "megaphone", "presentation", "calculator",
            // tech
            "laptop", "smartphone", "monitor", "server", "database", "cloud", "wifi", "cpu", "code",
            "terminal", "git-branch", "bug", "lock", "key", "shield-check", "fingerprint-pattern", "bot", "link",
            "mail", "message-circle", "bell", "search", "settings", "wrench", "rocket",
            // data and time
            "chart-bar", "chart-line", "chart-pie", "trending-up", "trending-down", "calendar", "clock",
            "hourglass", "timer", "file-text", "folder", "layers", "list-checks", "target", "flag",
            // ideas and learning
            "lightbulb", "brain", "book-open", "graduation-cap", "puzzle", "sparkles", "zap", "eye",
            "circle-question-mark", "circle-check", "circle-x", "triangle-alert", "thumbs-up", "thumbs-down",
            "trophy", "star", "heart", "gift",
            // transport, nature, health, daily life
            "car", "bus", "train-front", "plane", "ship", "bike", "sun", "moon", "cloud-rain", "droplet",
            "flame", "leaf", "trees", "recycle", "battery-charging", "plug", "heart-pulse", "stethoscope",
            "pill", "dumbbell", "apple", "coffee", "utensils", "camera", "eighth-notes", "headphones", "gamepad-2",
            "scale", "gavel");

    /**
     * Character poses the LLM may choose for "character" scenes. Keep in sync with the POSES map in
     * render-worker/src/Animated2D.tsx, which turns each into an Open Peeps body + face (9.4).
     */
    public static final List<String> POSES = List.of(
            "standing", "waving", "pointing", "presenting", "idea", "thinking", "confused", "surprised",
            "happy", "celebrating", "worried", "working", "phone", "walking", "sitting");

    /** type -> what the LLM fills (the order is the order the prompt lists them). */
    private static final Map<String, String> GUIDES = guides();

    private static final Map<Template, List<String>> TYPES = Map.of(
            Template.CLASSIC, List.of("title", "flow", "list", "stat", "compare", "code", "image", "cta"),
            Template.INFOGRAPHIC, List.of("title", "bars", "timeline", "cycle", "iconGrid", "stat", "flow",
                    "compare", "list", "cta"),
            Template.ANIMATED_2D, List.of("title", "character", "iconLink", "metaphor", "beforeAfter", "zoom",
                    "flow", "list", "stat", "cta"));

    private SceneCatalog() {
    }

    /** The scene types this template may use. */
    public static List<String> types(Template template) {
        return TYPES.get(template == null ? Template.CLASSIC : template);
    }

    /** The template a real video uses now: the chosen one if it is ready, otherwise CLASSIC. */
    public static Template effective(Template chosen) {
        return chosen != null && READY.contains(chosen) ? chosen : Template.CLASSIC;
    }

    /** The scene list for the prompt: one line per allowed type. */
    static String guide(Template template) {
        StringBuilder sb = new StringBuilder();
        for (String type : types(template)) {
            sb.append("  * \"").append(type).append("\": ").append(GUIDES.get(type)).append('\n');
        }
        return sb.toString();
    }

    /** True when the template uses icons from {@link #ICONS} (the prompt then lists them). */
    static boolean usesIcons(Template template) {
        return types(template).stream().anyMatch(t -> t.equals("iconGrid") || t.equals("iconLink")
                || t.equals("metaphor") || t.equals("beforeAfter") || t.equals("zoom") || t.equals("character"));
    }

    private static Map<String, String> guides() {
        Map<String, String> g = new LinkedHashMap<>();
        // shared and CLASSIC
        g.put("title", "title (max 5 words), subtitle. Good for the first scene.");
        g.put("flow", "items = 2-6 steps of a process, 1-4 words each (e.g. Code, Build, Test, Deploy).");
        g.put("list", "items = 2-5 points, max 8 words each.");
        g.put("stat", "value (a number), prefix, suffix (e.g. \"x\", \"%\"), label. Only for real, well-known numbers.");
        g.put("compare", "leftTitle, left (1-4 rows), rightTitle, right (1-4 rows). Before/after, bad/good.");
        g.put("code", "code (max 12 lines, max 48 characters per line), language (e.g. \"deploy.yml\").");
        g.put("image", "uses \"imagePrompt\" only. Only when a real-world picture really helps (a place, an object, a mood).");
        g.put("cta", "text (closing line), handle (short, may be empty). Good for the last scene.");
        // INFOGRAPHIC
        g.put("bars", "a bar chart. title, items = 2-6 bar labels (1-3 words), values = one number per item "
                + "(same count and order), suffix (e.g. \"%\", \"M\"). Only numbers from the idea or well known; "
                + "never invent data. If there are no real numbers, use another type.");
        g.put("timeline", "items = 3-6 events in order (max 6 words each), labels = one short date or step "
                + "per item (e.g. \"2019\", \"Day 1\", \"Step 2\").");
        g.put("cycle", "a loop that repeats. title = the word in the center (max 3 words), items = 3-6 stages "
                + "(1-3 words each).");
        g.put("iconGrid", "items = 3-6 points (max 5 words each), icons = one icon per item (same count and order).");
        // ANIMATED_2D
        g.put("character", "a 2D person. pose, text = what the person says in a speech bubble (max 10 words, "
                + "may be empty), icons = 0-2 icons shown next to the person. Good for the hook, a question "
                + "or a reaction. Use it for at most a third of the scenes.");
        g.put("iconLink", "2-4 icons joined by arrows, left to right (who sends what to whom). items = one label "
                + "per icon (1-3 words), icons = one icon per item (same count and order).");
        g.put("metaphor", "explains an idea with an everyday thing. title = the idea (max 4 words), subtitle = "
                + "the comparison (max 6 words, e.g. \"like a restaurant waiter\"), icons = [idea icon, metaphor icon].");
        g.put("beforeAfter", "leftTitle (the before), rightTitle (the after), icons = [before icon, after icon], "
                + "left and right = 0-3 short rows each.");
        g.put("zoom", "the camera zooms into one object to show its parts. title = the object (max 3 words), "
                + "icons = [object icon], items = 2-4 parts or details (1-4 words each).");
        return g;
    }
}
