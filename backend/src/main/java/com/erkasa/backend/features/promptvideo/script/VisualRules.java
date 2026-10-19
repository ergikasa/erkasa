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
            default -> {
                // image: checked through imagePrompt
            }
        }
        return problems;
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
