package com.erkasa.backend.features.promptvideo.project;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The video projects of the logged-in user.
 *
 *   POST /api/projects                          create; the script is written in the background (202)
 *   GET  /api/projects                          my projects, newest first
 *   GET  /api/projects/{id}                     status, script, jobs (poll this until SCRIPT_REVIEW)
 *   PUT  /api/projects/{id}/script              save an edited script (only in SCRIPT_REVIEW)
 *   POST /api/projects/{id}/regenerate-script   write a new script (at most 5 per project)
 *   POST /api/projects/{id}/approve             accept the script: images + voices start -> ASSETS_PENDING
 *   POST /api/projects/{id}/cancel              stop the project for good
 *   POST /api/projects/{id}/retry               FAILED -> redo only what failed
 *   POST /api/projects/{id}/scenes/{n}/redo     redo scene n's image and/or voice (ASSETS_READY)
 *   POST /api/projects/{id}/render              make the MP4 (ASSETS_READY -> RENDERING -> DONE)
 *   GET  /api/projects/{id}/video               the finished MP4 (?download=true to save it)
 *   GET  /api/projects/{id}/events              live updates (Server-Sent Events)
 *   GET  /api/projects/{id}/assets              every scene's image and voice (progress + links)
 *   GET  /api/projects/{id}/assets/{n}/image    the image file of scene n (0, 1, 2...)
 *   GET  /api/projects/{id}/assets/{n}/voice    the voice file of scene n
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;
    private final ObjectMapper json;

    public ProjectController(ProjectService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    public record CreateRequest(
            @NotBlank(message = "Write your video idea") @Size(max = 1000, message = "The idea is too long") String idea,
            @NotNull(message = "Choose a language") Language language,
            @Min(value = ScriptRequest.MIN_DURATION, message = "At least 15 seconds")
            @Max(value = ScriptRequest.MAX_DURATION, message = "At most 90 seconds") int durationSeconds,
            AspectRatio aspectRatio,
            @Size(max = 100) String voiceId) {
    }

    public record UpdateScriptRequest(@NotNull(message = "The script is missing") VideoScript script) {
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateRequest body, @AuthenticationPrincipal Jwt jwt) {
        Project project = service.create(userId(jwt), new ProjectService.NewProject(
                body.idea(), body.language(), body.durationSeconds(), body.aspectRatio(), body.voiceId()));
        // 202 Accepted: the project exists, the script is still being written.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(full(project));
    }

    @GetMapping
    public List<ProjectResponse> list(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), limit).stream().map(ProjectResponse::brief).toList();
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return full(service.get(userId(jwt), id));
    }

    @PutMapping("/{id}/script")
    public ProjectResponse updateScript(@PathVariable UUID id, @Valid @RequestBody UpdateScriptRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return full(service.updateScript(userId(jwt), id, body.script()));
    }

    @PostMapping("/{id}/regenerate-script")
    public ResponseEntity<ProjectResponse> regenerate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(full(service.regenerateScript(userId(jwt), id)));
    }

    @PostMapping("/{id}/approve")
    public ProjectResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return full(service.approve(userId(jwt), id));
    }

    @PostMapping("/{id}/cancel")
    public ProjectResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return full(service.cancel(userId(jwt), id));
    }

    /**
     * Live updates (Server-Sent Events). Each "project" event is a full snapshot:
     * {"projectId", "status", "title", "done", "total", "errorCode", "errorMessage", "at"}.
     *
     * Browsers: the built-in EventSource cannot send the Authorization header, so the frontend
     * uses fetch-based SSE (@microsoft/fetch-event-source) with "Authorization: Bearer ...".
     */
    @GetMapping("/{id}/events")
    public SseEmitter events(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.subscribe(userId(jwt), id);
    }

    /** "Try again" on a FAILED project: redoes only what failed. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ProjectResponse> retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(full(service.retry(userId(jwt), id)));
    }

    /**
     * What to redo for one scene. image / voice: redo as it is. imagePrompt / narration: change
     * the scene first (implies redoing that part).
     *
     * Boolean (not boolean): Jackson 3 refuses a missing field for a primitive, and {"image":true}
     * without "voice" must be valid. null means false.
     */
    public record RedoRequest(Boolean image, Boolean voice,
                              @Size(max = 2000) String imagePrompt,
                              @Size(max = 1000) String narration) {
    }

    /** Redo one scene's image and/or voice after ASSETS_READY; the other scenes are kept. */
    @PostMapping("/{id}/scenes/{scene}/redo")
    public ResponseEntity<ProjectResponse> redo(@PathVariable UUID id, @PathVariable int scene,
                                                @Valid @RequestBody RedoRequest body, @AuthenticationPrincipal Jwt jwt) {
        Project project = service.redoScene(userId(jwt), id, scene,
                Boolean.TRUE.equals(body.image()), Boolean.TRUE.equals(body.voice()),
                body.imagePrompt(), body.narration());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(full(project));
    }

    /** Make the MP4 from the script + files (ASSETS_READY -> RENDERING -> DONE). 202: it takes a minute or two. */
    @PostMapping("/{id}/render")
    public ResponseEntity<ProjectResponse> render(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(full(service.render(userId(jwt), id)));
    }

    /** The finished video (status DONE). Add ?download=true to save it as a file. */
    @GetMapping("/{id}/video")
    public ResponseEntity<byte[]> video(@PathVariable UUID id,
                                        @RequestParam(defaultValue = "false") boolean download,
                                        @AuthenticationPrincipal Jwt jwt) {
        ProjectService.Asset video = service.video(userId(jwt), id);
        ResponseEntity.BodyBuilder res = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(video.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate());
        if (download) {
            res.header("Content-Disposition", "attachment; filename=\"erkasa-" + id + ".mp4\"");
        }
        return res.body(video.data());
    }

    /** Every scene's image and voice: status, download link, size / duration / word timings. */
    @GetMapping("/{id}/assets")
    public AssetsResponse assets(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return AssetsResponse.of(id, service.assetStepsOf(userId(jwt), id), this::readMeta);
    }

    /** The image of one scene (scenes count from 0). */
    @GetMapping("/{id}/assets/{scene}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id, @PathVariable int scene, @AuthenticationPrincipal Jwt jwt) {
        return file(service.asset(userId(jwt), id, scene, StepType.IMAGE));
    }

    /** The voice-over of one scene. */
    @GetMapping("/{id}/assets/{scene}/voice")
    public ResponseEntity<byte[]> voice(@PathVariable UUID id, @PathVariable int scene, @AuthenticationPrincipal Jwt jwt) {
        return file(service.asset(userId(jwt), id, scene, StepType.VOICE));
    }

    private static ResponseEntity<byte[]> file(ProjectService.Asset asset) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                // Files never change once written (a redo gets a new key), so browsers may keep them.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(asset.data());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(String metaJson) {
        return json.readValue(metaJson, Map.class);
    }

    private ProjectResponse full(Project project) {
        return ProjectResponse.of(project, service.scriptOf(project), service.jobsOf(project.getId()));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
