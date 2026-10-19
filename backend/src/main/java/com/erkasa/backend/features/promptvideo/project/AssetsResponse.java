package com.erkasa.backend.features.promptvideo.project;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;

/**
 * GET /api/projects/{id}/assets: every scene with the state of its image and its voice.
 *
 * @param done    steps finished (SUCCEEDED), for a progress bar: done / total
 * @param costUsd what the providers charged us for these files so far (internal; hidden from users in phase 6)
 */
public record AssetsResponse(UUID projectId, int done, int total, BigDecimal costUsd, List<SceneAssets> scenes) {

    public record SceneAssets(int scene, StepView image, StepView voice) {
    }

    /**
     * @param url  where to download the file once status is SUCCEEDED, otherwise null
     * @param meta image: width, height; voice: durationSeconds and the word timings
     */
    public record StepView(StepStatus status, int attempts, String url, Map<String, Object> meta,
                           String errorCode, String errorMessage) {
    }

    interface MetaReader {
        Map<String, Object> read(String json);
    }

    static AssetsResponse of(UUID projectId, List<JobStep> steps, MetaReader meta) {
        Map<Integer, StepView[]> byScene = new TreeMap<>();
        int done = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (JobStep s : steps) {
            if (!s.getType().perScene()) {
                continue;
            }
            String url = s.getStatus() == StepStatus.SUCCEEDED
                    ? "/api/projects/" + projectId + "/assets/" + s.getSceneIndex() + "/" + s.getType().name().toLowerCase()
                    : null;
            StepView view = new StepView(s.getStatus(), s.getAttempts(), url,
                    s.getOutputMeta() == null ? null : meta.read(s.getOutputMeta()),
                    s.getErrorCode(), s.getErrorMessage());
            StepView[] pair = byScene.computeIfAbsent(s.getSceneIndex(), k -> new StepView[2]);
            pair[s.getType() == StepType.IMAGE ? 0 : 1] = view;
            if (s.getStatus() == StepStatus.SUCCEEDED) {
                done++;
            }
            cost = cost.add(s.getCostUsd());
        }
        List<SceneAssets> scenes = new ArrayList<>();
        byScene.forEach((scene, pair) -> scenes.add(new SceneAssets(scene, pair[0], pair[1])));
        return new AssetsResponse(projectId, done, scenes.size() * 2, cost, scenes);
    }
}