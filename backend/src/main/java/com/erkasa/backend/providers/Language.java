package com.erkasa.backend.providers;


/** The video languages erkasa supports in v1. Voice and subtitles use the same language. */
public enum Language {
    EN("English"),
    DE("German"),
    ES("Spanish"),
    FR("French"),
    IT("Italian"),
    MK("Macedonian"),
    SR("Serbian"),
    RU("Russian"),
    SQ("Albanian");

    private final String displayName;

    Language(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}