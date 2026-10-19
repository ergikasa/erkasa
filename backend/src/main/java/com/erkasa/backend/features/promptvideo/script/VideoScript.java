package com.erkasa.backend.features.promptvideo.script;



import java.util.List;

/**
 * The script of one video, as written by the LLM. Field names match the JSON schema
 * in {@link ScriptSchema}, so the JSON maps straight onto these records.
 *
 * @param title     short title of the video
 * @param musicMood one of: calm, upbeat, dramatic, inspiring, mysterious, fun
 * @param scenes    in playing order
 */
public record VideoScript(String title, String musicMood, List<Scene> scenes) {

    /**
     * @param narration    what the voice says, in the video language
     * @param onScreenText a few big words shown on screen (may be empty), in the video language
     * @param imagePrompt  English description for the image model (only used when visual is "image")
     * @param visual       what the scene shows; null in scripts written before visuals = "image"
     */
    public record Scene(String narration, String onScreenText, String imagePrompt, Visual visual) {

        /** An image scene, as in scripts written before visuals existed. */
        public Scene(String narration, String onScreenText, String imagePrompt) {
            this(narration, onScreenText, imagePrompt, null);
        }

        /** True when this scene needs an image from the image model. */
        public boolean needsImage() {
            return Visual.isImage(visual);
        }
    }

    /** Total words of narration, used to estimate how long the voice will speak. */
    public int narrationWords() {
        return scenes == null ? 0 : scenes.stream()
                .mapToInt(s -> ScriptValidator.countWords(s.narration()))
                .sum();
    }
}