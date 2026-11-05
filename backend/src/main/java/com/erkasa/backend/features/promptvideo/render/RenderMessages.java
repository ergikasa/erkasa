package com.erkasa.backend.features.promptvideo.render;

import java.util.List;
import java.util.UUID;

import com.erkasa.backend.features.promptvideo.script.SoundCue;
import com.erkasa.backend.features.promptvideo.script.Visual;
import com.erkasa.backend.providers.tts.WordTiming;

/**
 * The JSON sent over RabbitMQ. Field names match render-worker/src/spec.ts and worker.ts.
 */
public final class RenderMessages {

    private RenderMessages() {
    }

    /**
     * backend -> worker: render this video and upload it to outputUrl (HTTP PUT, Content-Type video/mp4).
     *
     * @param dispatchId this send of the job (V2 A3); every result must carry it back, or it is ignored
     * @param attempt    1 for the first send, 2 for the next one ...
     * @param timeoutSeconds the render time limit (V2 A4); the worker stops itself at 90 % of it
     */
    public record Request(UUID jobId, UUID projectId, UUID dispatchId, int attempt, long timeoutSeconds,
                          String outputKey, String outputUrl, Spec spec) {
    }

    /** The render spec (see RenderSpec in spec.ts). image / audio are presigned http(s) links. */
    /** @param theme colour theme of the graphics: DARK, LIGHT, VIBRANT (9.3) */
    public record Spec(String aspectRatio, int fps, String title, String music, double musicVolume,
                       boolean captions, String theme, List<Scene> scenes) {
    }

    public record Scene(String image, String audio, double audioSeconds, String onScreenText,
                        List<WordTiming> words, Visual visual, List<SoundCue> sfx) {
    }

    /**
     * worker -> backend.
     *
     * @param type            CLAIMED (the worker starts: takes the lease), PROGRESS (percent; also the
     *                        heartbeat every 20 s that keeps the lease), SUCCEEDED or FAILED
     * @param dispatchId      copied from the request (fencing, V2 A3)
     * @param workerId        which worker process sends this (lease owner)
     * @param percent         0-100 while rendering
     * @param durationSeconds length of the finished MP4
     * @param bytes           size of the finished MP4
     * @param renderSeconds   how long the render took (to plan worker capacity)
     * @param error           why it failed, for FAILED
     * @param errorCode       for FAILED: RENDER_TIMEOUT when the worker hit its time limit (V2 A4)
     */
    public record Result(UUID jobId, String type, UUID dispatchId, String workerId, Integer percent,
                         Double durationSeconds, Long bytes, Double renderSeconds, String error,
                         String errorCode) {

        public static final String CLAIMED = "CLAIMED";
        public static final String PROGRESS = "PROGRESS";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";
    }
}
