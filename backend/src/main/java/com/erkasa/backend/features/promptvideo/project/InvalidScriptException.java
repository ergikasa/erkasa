package com.erkasa.backend.features.promptvideo.project;

import java.util.List;

/**
 * The script the user sent breaks the rules (too few scenes, narration too long...).
 * Becomes HTTP 422 with "code": "INVALID_SCRIPT" and the list of problems, so the UI can show them.
 */
public class InvalidScriptException extends RuntimeException {

    private final List<String> problems;

    public InvalidScriptException(List<String> problems) {
        super("The script is not valid: " + String.join(" ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}