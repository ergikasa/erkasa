package com.erkasa.backend.jobs;

/** One unit of paid work. IMAGE and VOICE exist once per scene; SCRIPT and RENDER once per video. */
public enum StepType {
    SCRIPT,
    IMAGE,
    VOICE,
    RENDER;

    /** True for steps that belong to one scene (scene_index 0, 1, 2...). */
    public boolean perScene() {
        return this == IMAGE || this == VOICE;
    }
}