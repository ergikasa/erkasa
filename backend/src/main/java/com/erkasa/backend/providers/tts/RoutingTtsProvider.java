package com.erkasa.backend.providers.tts;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.erkasa.backend.providers.ProviderException;

/**
 * The real TTS provider. Active when erkasa.providers.tts=elevenlabs-azure.
 *
 * For each request it looks up the voice in tts_voices (the one the user picked, or the
 * language's default) and sends the text to that voice's engine:
 *   EN, DE, ES, FR, IT, RU -> ElevenLabs      SQ, MK, SR -> Azure
 * Moving a language to another provider is a row change in tts_voices, not a code change.
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "tts", havingValue = "elevenlabs-azure")
public class RoutingTtsProvider implements TtsProvider {

    static final String NAME = "elevenlabs-azure";

    private final TtsVoiceRepository voices;
    private final Map<String, VoiceEngine> engines;

    public RoutingTtsProvider(TtsVoiceRepository voices, List<VoiceEngine> engines) {
        this.voices = voices;
        this.engines = engines.stream().collect(Collectors.toMap(VoiceEngine::provider, Function.identity()));
    }

    @Override
    public String name() {
        return NAME;
    }

    /** The exact model and voice of each call is saved in provider_usage.model, e.g. azure/sq-AL-IlirNeural. */
    @Override
    public String model() {
        return "routed";
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ProviderException(NAME, "BAD_REQUEST", "Text is empty", false, null);
        }
        TtsVoice voice = pick(request);
        VoiceEngine engine = engines.get(voice.getProvider());
        if (engine == null) {
            throw new ProviderException(NAME, "NO_ENGINE",
                    "No engine for provider " + voice.getProvider(), false, null);
        }
        return engine.synthesize(request.text().strip(), request.language(), voice.getVoiceId());
    }

    private TtsVoice pick(SpeechRequest request) {
        if (request.language() == null) {
            throw new ProviderException(NAME, "BAD_REQUEST", "Language is missing", false, null);
        }
        if (request.voiceId() != null && !request.voiceId().isBlank()) {
            return voices.findFirstByLanguageAndVoiceIdAndActiveTrue(request.language(), request.voiceId())
                    .orElseThrow(() -> new ProviderException(NAME, "UNKNOWN_VOICE",
                            "Voice " + request.voiceId() + " is not available for " + request.language(),
                            false, null));
        }
        return voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(request.language())
                .orElseThrow(() -> new ProviderException(NAME, "NO_VOICE",
                        "No default voice for " + request.language() + " in tts_voices", false, null));
    }
}