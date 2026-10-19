package com.erkasa.backend.jobs;

/** Small text helpers for columns with a fixed length. */
final class Text {

    private Text() {
    }

    /** Cuts s so it fits a VARCHAR(max) column; null stays null. */
    static String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}