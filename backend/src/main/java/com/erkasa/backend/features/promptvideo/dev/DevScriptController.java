package com.erkasa.backend.features.promptvideo.dev;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erkasa.backend.features.promptvideo.project.ProjectController;
import com.erkasa.backend.features.promptvideo.script.ScriptGenerator;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.Language;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DEV ONLY: generate a script by hand. In phase 4 this becomes the first step of a real video job.
 *
 * 9.2: send "options" like POST /api/projects ({"purpose": "BUSINESS", "template": "INFOGRAPHIC", ...})
 * to see the script the LLM writes for a template, even one the render worker cannot draw yet.
 */
@RestController
@RequestMapping("/api/dev/script")
@Profile("dev")
public class DevScriptController {

    private final ScriptGenerator scriptGenerator;

    public DevScriptController(ScriptGenerator scriptGenerator) {
        this.scriptGenerator = scriptGenerator;
    }

    public record Input(
            @NotBlank @Size(max = 1000) String idea,
            @NotNull Language language,
            @Min(ScriptRequest.MIN_DURATION) @Max(ScriptRequest.MAX_DURATION) int durationSeconds,
            AspectRatio aspectRatio,
            @Valid ProjectController.OptionsRequest options) {
    }

    @PostMapping
    public VideoScript generate(@Valid @RequestBody Input input, @AuthenticationPrincipal Jwt jwt) {
        AspectRatio ratio = input.aspectRatio() == null ? AspectRatio.PORTRAIT : input.aspectRatio();
        // 9.2: no fallback here, so you can preview scripts for templates the worker cannot draw yet.
        ScriptRequest request = new ScriptRequest(input.idea(), input.language(), input.durationSeconds(), ratio,
                input.options() == null ? null : input.options().complete());
        return scriptGenerator.generate(request, CallContext.forUser(UUID.fromString(jwt.getSubject())));
    }
}