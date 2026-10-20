package com.erkasa.backend.features.promptvideo.render;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.erkasa.backend.assets.AssetStorage;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.tts.WordTiming;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a project (script) + its finished ASSETS job (files in storage) into the render spec
 * the worker draws. Files are passed as presigned links, valid long enough for a slow render.
 */
@Component
public class RenderSpecBuilder {

    static final Duration LINK_VALID = Duration.ofHours(3);
    static final int FPS = 30;
    static final double MUSIC_VOLUME = 0.12;

    private final AssetStorage storage;
    private final ObjectMapper json;

    public RenderSpecBuilder(AssetStorage storage, ObjectMapper json) {
        this.storage = storage;
        this.json = json;
    }

    public RenderMessages.Spec build(Project project, VideoScript script, List<JobStep> assetSteps) {
        Map<String, JobStep> done = assetSteps.stream()
                .filter(s -> s.getStatus() == StepStatus.SUCCEEDED && s.getType().perScene())
                .collect(Collectors.toMap(s -> s.getType() + "#" + s.getSceneIndex(), Function.identity()));

        List<RenderMessages.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < script.scenes().size(); i++) {
            VideoScript.Scene scene = script.scenes().get(i);
            JobStep voice = done.get(StepType.VOICE + "#" + i);
            if (voice == null) {
                throw new IllegalStateException("Scene " + i + " has no finished voice-over");
            }
            JobStep image = done.get(StepType.IMAGE + "#" + i);
            if (scene.needsImage() && image == null) {
                throw new IllegalStateException("Scene " + i + " has no finished image");
            }
            JsonNode meta = json.readTree(voice.getOutputMeta());
            List<WordTiming> words = new ArrayList<>();
            JsonNode list = meta.path("words");
            for (int k = 0; k < list.size(); k++) {
                JsonNode w = list.get(k);
                words.add(new WordTiming(w.path("word").asString(), w.path("startSeconds").asDouble(),
                        w.path("endSeconds").asDouble()));
            }
            scenes.add(new RenderMessages.Scene(
                    image == null ? null : storage.presignGet(image.getOutputKey(), LINK_VALID),
                    storage.presignGet(voice.getOutputKey(), LINK_VALID),
                    meta.path("durationSeconds").asDouble(),
                    scene.onScreenText() == null ? "" : scene.onScreenText(),
                    words,
                    scene.visual()));
        }
        // Music comes in 5.4 (assets folder + ducking).
        return new RenderMessages.Spec(project.getAspectRatio().name(), FPS, script.title(), null, MUSIC_VOLUME,
                true, scenes);
    }
}
