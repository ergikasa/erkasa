package com.erkasa.backend.features.promptvideo.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a scene's visual can be drawn well: enough items to animate, not so much text
 * that it does not fit a phone screen. Problems are written for the LLM, so it can fix them.
 */
final class VisualRules {

    static final int MAX_STEP_WORDS = 4;
    static final int MAX_ITEM_WORDS = 8;
    static final int MAX_CODE_LINES = 12;
    static final int MAX_CODE_LINE_CHARS = 48;

    private VisualRules() {
    }

    static List<String> check(Visual v, int scene) {
        List<String> problems = new ArrayList<>();
        if (v == null || v.type() == null) {
            return problems; // old script: an image scene
        }
        String at = "Scene " + scene + " visual (" + v.type() + "): ";
        if (!Visual.TYPES.contains(v.type())) {
            problems.add("Scene " + scene + " has an unknown visual type \"" + v.type() + "\".");
            return problems;
        }
        switch (v.type()) {
            case "title" -> {
                if (blank(v.title())) problems.add(at + "title is empty.");
                else if (words(v.title()) > 5) problems.add(at + "title must be at most 5 words.");
            }
            case "flow" -> {
                List<String> steps = list(v.items());
                if (steps.size() < 2 || steps.size() > 6) problems.add(at + "use 2 to 6 steps in items.");
                if (steps.stream().anyMatch(st -> blank(st) || words(st) > MAX_STEP_WORDS)) {
                    problems.add(at + "every step must be 1 to " + MAX_STEP_WORDS + " words.");
                }
            }
            case "list" -> {
                List<String> items = list(v.items());
                if (items.size() < 2 || items.size() > 5) problems.add(at + "use 2 to 5 items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > MAX_ITEM_WORDS)) {
                    problems.add(at + "every item must be 1 to " + MAX_ITEM_WORDS + " words.");
                }
            }
            case "stat" -> {
                if (v.value() == null || v.value() <= 0) problems.add(at + "value must be a positive number.");
                if (blank(v.label())) problems.add(at + "label is empty.");
            }
            case "compare" -> {
                if (blank(v.leftTitle()) || blank(v.rightTitle())) problems.add(at + "both leftTitle and rightTitle are needed.");
                int l = list(v.left()).size();
                int r = list(v.right()).size();
                if (l < 1 || l > 4 || r < 1 || r > 4) problems.add(at + "use 1 to 4 rows on each side.");
                if (concat(v.left(), v.right()).stream().anyMatch(it -> words(it) > MAX_ITEM_WORDS)) {
                    problems.add(at + "every row must be at most " + MAX_ITEM_WORDS + " words.");
                }
            }
            case "code" -> {
                if (blank(v.code())) {
                    problems.add(at + "code is empty.");
                } else {
                    String[] lines = v.code().split("\n");
                    if (lines.length > MAX_CODE_LINES) problems.add(at + "use at most " + MAX_CODE_LINES + " lines.");
                    for (String line : lines) {
                        if (line.length() > MAX_CODE_LINE_CHARS) {
                            problems.add(at + "lines must be at most " + MAX_CODE_LINE_CHARS + " characters (phone screen).");
                            break;
                        }
                    }
                }
            }
            case "cta" -> {
                if (blank(v.text())) problems.add(at + "text is empty.");
            }
            // ---- INFOGRAPHIC (9.2) ----
            case "bars" -> {
                List<String> items = list(v.items());
                if (items.size() < 2 || items.size() > 6) problems.add(at + "use 2 to 6 bars in items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 3)) problems.add(at + "every bar label must be 1 to 3 words.");
                List<Double> values = v.values() == null ? List.of() : v.values();
                if (values.size() != items.size()) {
                    problems.add(at + "values must have one number per item (" + items.size() + " items, " + values.size() + " values).");
                } else if (values.stream().anyMatch(x -> x == null || x < 0)) {
                    problems.add(at + "values must be numbers of 0 or more.");
                }
            }
            case "timeline" -> {
                List<String> items = list(v.items());
                if (items.size() < 3 || items.size() > 6) problems.add(at + "use 3 to 6 events in items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 6)) problems.add(at + "every event must be 1 to 6 words.");
                if (list(v.labels()).size() != items.size()) problems.add(at + "labels must have one date or step per item.");
            }
            case "cycle" -> {
                List<String> items = list(v.items());
                if (items.size() < 3 || items.size() > 6) problems.add(at + "use 3 to 6 stages in items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 3)) problems.add(at + "every stage must be 1 to 3 words.");
                if (words(v.title()) > 3) problems.add(at + "title (the center) must be at most 3 words.");
            }
            case "iconGrid" -> {
                List<String> items = list(v.items());
                if (items.size() < 3 || items.size() > 6) problems.add(at + "use 3 to 6 items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 5)) problems.add(at + "every item must be 1 to 5 words.");
                iconCount(problems, at, v, items.size(), items.size());
            }
            // ---- ANIMATED_2D (9.2) ----
            case "character" -> {
                if (blank(v.pose()) || !SceneCatalog.POSES.contains(v.pose())) {
                    problems.add(at + "pose must be one of " + SceneCatalog.POSES + ".");
                }
                if (words(v.text()) > 10) problems.add(at + "the speech bubble (text) must be at most 10 words.");
                iconCount(problems, at, v, 0, 2);
            }
            case "iconLink" -> {
                List<String> items = list(v.items());
                if (items.size() < 2 || items.size() > 4) problems.add(at + "use 2 to 4 items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 3)) problems.add(at + "every label must be 1 to 3 words.");
                iconCount(problems, at, v, items.size(), items.size());
            }
            case "metaphor" -> {
                if (blank(v.title()) || words(v.title()) > 4) problems.add(at + "title (the idea) must be 1 to 4 words.");
                if (blank(v.subtitle()) || words(v.subtitle()) > 6) problems.add(at + "subtitle (the comparison) must be 1 to 6 words.");
                iconCount(problems, at, v, 2, 2);
            }
            case "beforeAfter" -> {
                if (blank(v.leftTitle()) || blank(v.rightTitle())) problems.add(at + "both leftTitle and rightTitle are needed.");
                if (list(v.left()).size() > 3 || list(v.right()).size() > 3) problems.add(at + "use at most 3 rows on each side.");
                iconCount(problems, at, v, 2, 2);
            }
            case "zoom" -> {
                if (blank(v.title()) || words(v.title()) > 3) problems.add(at + "title (the object) must be 1 to 3 words.");
                List<String> items = list(v.items());
                if (items.size() < 2 || items.size() > 4) problems.add(at + "use 2 to 4 details in items.");
                if (items.stream().anyMatch(it -> blank(it) || words(it) > 4)) problems.add(at + "every detail must be 1 to 4 words.");
                iconCount(problems, at, v, 1, 1);
            }
            default -> {
                // image: checked through imagePrompt
            }
        }
        return problems;
    }

    /** Between min and max icons, all from SceneCatalog.ICONS. */
    private static void iconCount(List<String> problems, String at, Visual v, int min, int max) {
        List<String> icons = list(v.icons());
        if (icons.size() < min || icons.size() > max) {
            problems.add(at + (min == max ? "use exactly " + min : "use " + min + " to " + max) + " icons.");
        }
        List<String> unknown = icons.stream().filter(i -> !SceneCatalog.ICONS.contains(i)).toList();
        if (!unknown.isEmpty()) {
            problems.add(at + "unknown icons " + unknown + "; use only names from the icon list.");
        }
    }

    private static List<String> list(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(list(a));
        all.addAll(list(b));
        return all;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int words(String s) {
        return ScriptValidator.countWords(s);
    }
}
