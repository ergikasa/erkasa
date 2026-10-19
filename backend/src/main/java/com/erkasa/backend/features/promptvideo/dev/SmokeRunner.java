package com.erkasa.backend.features.promptvideo.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.erkasa.backend.features.promptvideo.script.ScriptGenerator;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.ProviderGateway;
import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.tts.SpeechRequest;
import com.erkasa.backend.providers.tts.SpeechResult;
import com.erkasa.backend.providers.tts.WordTiming;

import tools.jackson.databind.ObjectMapper;

/**
 * Sub-task 3.5 (DEV ONLY): one idea through all three real providers, like a mini video job.
 *
 *   idea -> script (LLM) -> for the first N scenes: image + voice -> files on disk + cost summary
 *
 * Every call is made with the same jobId, so provider_usage shows exactly what this run cost.
 * Phase 4 replaces this with the real pipeline (jobs table, storage, retries per step).
 */
@Service
@Profile("dev")
public class SmokeRunner {

    private static final Logger log = LoggerFactory.getLogger(SmokeRunner.class);

    private final ScriptGenerator scripts;
    private final ProviderGateway gateway;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Path outputRoot;

    public SmokeRunner(ScriptGenerator scripts,
                       ProviderGateway gateway,
                       JdbcTemplate jdbc,
                       ObjectMapper json,
                       @Value("${erkasa.dev.smoke-output-dir:smoke-output}") String outputDir) {
        this.scripts = scripts;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.json = json;
        this.outputRoot = Path.of(outputDir).toAbsolutePath().normalize();
    }

    public record SceneResult(int scene,
                              String narration,
                              String imageFile,
                              int imageWidth,
                              int imageHeight,
                              String audioFile,
                              double audioSeconds,
                              List<WordTiming> words,
                              com.erkasa.backend.features.promptvideo.script.Visual visual) {
    }

    public record CostLine(String kind, String provider, String model, long calls, long failed, BigDecimal costUsd) {
    }

    public record Result(UUID jobId,
                         String outputDir,
                         String title,
                         int scenesInScript,
                         List<SceneResult> rendered,
                         List<CostLine> costs,
                         BigDecimal totalCostUsd,
                         long durationMs) {
    }

    /**
     * @param scenesToRender how many scenes get an image and a voice (the rest only have script).
     *                       Keep it small while testing: every scene costs an image + a voice-over.
     */
    public Result run(ScriptRequest request, int scenesToRender, UUID userId) {
        long start = System.currentTimeMillis();
        UUID jobId = UUID.randomUUID();
        CallContext ctx = new CallContext(userId, jobId);
        Path dir = outputRoot.resolve(jobId.toString());
        mkdirs(dir);
        log.info("Smoke run {}: '{}' ({}, {} s), files in {}",
                jobId, request.idea(), request.language(), request.durationSeconds(), dir);

        VideoScript script = scripts.generate(request, ctx);
        write(dir.resolve("script.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(script));

        int count = Math.min(scenesToRender, script.scenes().size());
        List<SceneResult> rendered = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            VideoScript.Scene scene = script.scenes().get(i);
            int n = i + 1;

            // Motion-graphics scenes have no image: the render worker draws them.
            String imageFile = null;
            int imageWidth = 0;
            int imageHeight = 0;
            if (scene.needsImage()) {
                GeneratedImage image = gateway.generateImage(
                        new ImageRequest(scene.imagePrompt(), request.aspectRatio()), ctx);
                imageFile = "scene-" + n + "." + extension(image.contentType());
                write(dir.resolve(imageFile), image.data());
                imageWidth = image.width();
                imageHeight = image.height();
            }

            SpeechResult voice = gateway.synthesize(
                    new SpeechRequest(scene.narration(), request.language(), null), ctx);
            String audioFile = "scene-" + n + "." + extension(voice.contentType());
            write(dir.resolve(audioFile), voice.audio());
            write(dir.resolve("scene-" + n + ".words.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(voice.words()));

            rendered.add(new SceneResult(n, scene.narration(), imageFile, imageWidth, imageHeight,
                    audioFile, voice.durationSeconds(), voice.words(), scene.visual()));
            log.info("Smoke run {}: scene {}/{} done", jobId, n, count);
        }

        List<CostLine> costs = costs(jobId);
        BigDecimal total = costs.stream().map(CostLine::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        Result result = new Result(jobId, dir.toString(), script.title(), script.scenes().size(),
                rendered, costs, total, System.currentTimeMillis() - start);
        write(dir.resolve("summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        log.info("Smoke run {} finished: {} scenes rendered, total ${}", jobId, rendered.size(), total);
        return result;
    }

    /** What provider_usage recorded for this run, one line per kind + provider + model. */
    private List<CostLine> costs(UUID jobId) {
        return jdbc.query("""
                        select kind, provider, model,
                               count(*)                         as calls,
                               count(*) filter (where not success) as failed,
                               coalesce(sum(cost_usd), 0)       as cost
                        from provider_usage
                        where job_id = ?
                        group by kind, provider, model
                        order by kind, provider, model
                        """,
                (rs, i) -> new CostLine(rs.getString("kind"), rs.getString("provider"), rs.getString("model"),
                        rs.getLong("calls"), rs.getLong("failed"), rs.getBigDecimal("cost")),
                jobId);
    }

    static String extension(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            default -> "bin";
        };
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
    }

    private static void write(Path file, String text) {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path file, byte[] data) {
        try {
            Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
