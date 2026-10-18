package com.erkasa.backend.providers.dev;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderGateway;
import com.erkasa.backend.providers.ProviderUsage;
import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.LlmResponse;
import com.erkasa.backend.providers.tts.SpeechRequest;
import com.erkasa.backend.providers.tts.SpeechResult;
import com.erkasa.backend.providers.tts.WordTiming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DEV ONLY (@Profile("dev")): try each provider by hand before the video pipeline exists.
 * Does not exist in production. Still needs a logged-in user, and every call is recorded
 * in provider_usage like a real one.
 */
@RestController
@RequestMapping("/api/dev/providers")
@Profile("dev")
public class DevProvidersController {

    private final ProviderGateway gateway;

    public DevProvidersController(ProviderGateway gateway) {
        this.gateway = gateway;
    }

    public record TextRequest(@NotBlank @Size(max = 4000) String prompt) {
    }

    public record ImageInput(@NotBlank @Size(max = 2000) String prompt, AspectRatio aspectRatio) {
    }

    public record TtsInput(@NotBlank @Size(max = 2000) String text, @NotNull Language language, String voiceId) {
    }

    /** What /tts returns as JSON: everything except the audio bytes. */
    public record TtsInfo(String contentType, double durationSeconds, int audioBytes,
                          List<WordTiming> words, ProviderUsage usage) {
    }

    @PostMapping("/llm")
    public LlmResponse llm(@Valid @RequestBody TextRequest body, @AuthenticationPrincipal Jwt jwt) {
        return gateway.generateText(
                new LlmRequest("You are a helpful assistant.", body.prompt(), 1000), context(jwt));
    }

    /** Returns the image itself (PNG/JPEG). Save it with curl -o image.png */
    @PostMapping("/image")
    public ResponseEntity<byte[]> image(@Valid @RequestBody ImageInput body, @AuthenticationPrincipal Jwt jwt) {
        AspectRatio ratio = body.aspectRatio() == null ? AspectRatio.PORTRAIT : body.aspectRatio();
        GeneratedImage image = gateway.generateImage(new ImageRequest(body.prompt(), ratio), context(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.data());
    }

    /**
     * Without ?download=true: JSON with duration and word timings.
     * With ?download=true: the audio file itself. Save it with curl -o voice.wav
     */
    @PostMapping("/tts")
    public ResponseEntity<?> tts(@Valid @RequestBody TtsInput body,
                                 @RequestParam(defaultValue = "false") boolean download,
                                 @AuthenticationPrincipal Jwt jwt) {
        SpeechResult speech = gateway.synthesize(
                new SpeechRequest(body.text(), body.language(), body.voiceId()), context(jwt));
        if (download) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(speech.contentType()))
                    .body(speech.audio());
        }
        return ResponseEntity.ok(new TtsInfo(speech.contentType(), speech.durationSeconds(),
                speech.audio().length, speech.words(), speech.usage()));
    }

    private static CallContext context(Jwt jwt) {
        return CallContext.forUser(UUID.fromString(jwt.getSubject()));
    }
}