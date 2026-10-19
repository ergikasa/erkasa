package com.erkasa.backend.jobs;

/** The three stages of making a video. Each stage is one job. */
public enum JobKind {
    /** The LLM writes the script (one step). */
    SCRIPT,
    /** One IMAGE and one VOICE step per scene, run in parallel. */
    ASSETS,
    /** The render worker turns everything into an MP4 (phase 5, one step). */
    RENDER
}