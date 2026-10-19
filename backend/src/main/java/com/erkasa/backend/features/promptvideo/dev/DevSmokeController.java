package com.erkasa.backend.features.promptvideo.dev;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DEV ONLY (3.5): POST /api/dev/smoke runs script + image + voice for one idea.
 * Files land in backend/smoke-output/{jobId}/ ; the answer lists them with the cost of the run.
 */
@RestController
@RequestMapping("/api/dev/smoke")
@Profile("dev")
public class DevSmokeController {

    private final SmokeRunner runner;

    public DevSmokeController(SmokeRunner runner) {
        this.runner = runner;
    }

    /**
     * @param scenes how many scenes get an image + voice: 1 by default to keep test costs low
     */
    public record Input(
            @NotBlank @Size(max = 1000) String idea,
            @NotNull Language language,
            @Min(ScriptRequest.MIN_DURATION) @Max(ScriptRequest.MAX_DURATION) int durationSeconds,
            AspectRatio aspectRatio,
            @Min(1) @Max(12) Integer scenes) {
    }

    @PostMapping
    public SmokeRunner.Result run(@Valid @RequestBody Input input, @AuthenticationPrincipal Jwt jwt) {
        AspectRatio ratio = input.aspectRatio() == null ? AspectRatio.PORTRAIT : input.aspectRatio();
        int scenes = input.scenes() == null ? 1 : input.scenes();
        ScriptRequest request = new ScriptRequest(input.idea(), input.language(), input.durationSeconds(), ratio);
        return runner.run(request, scenes, UUID.fromString(jwt.getSubject()));
    }
}