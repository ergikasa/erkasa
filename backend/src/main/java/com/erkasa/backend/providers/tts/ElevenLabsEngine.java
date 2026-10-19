package com.erkasa.backend.providers.tts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderHttp;
import com.erkasa.backend.providers.ProviderUsage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ElevenLabs text-to-speech WITH timestamps: one call returns the MP3 and the start/end time
 * of every character, which we group into words for the subtitles.
 *
 * POST /v1/text-to-speech/{voice_id}/with-timestamps
 * Docs: https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "tts", havingValue = "elevenlabs-azure")
@EnableConfigurationProperties(ElevenLabsProperties.class)
public class ElevenLabsEngine implements VoiceEngine {

    static final String NAME = "elevenlabs";
    private static final Logger log = LoggerFactory.getLogger(ElevenLabsEngine.class);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final ElevenLabsProperties props;
    private final RestClient client;

    public ElevenLabsEngine(ElevenLabsProperties props, ProviderHttp http) {
        this.props = props;
        this.client = http.client(props.baseUrl(), props.timeout());
        if (!configured()) {
            log.warn("ELEVENLABS_API_KEY is not set: voices with provider=elevenlabs will fail");
        }
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    public SpeechResult synthesize(String text, Language language, String voiceId) {
        if (!configured()) {
            throw new ProviderException(NAME, "NOT_CONFIGURED", "ELEVENLABS_API_KEY is not set", false, null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("model_id", props.model());
        body.put("language_code", language.name().toLowerCase(Locale.ROOT));

        Response response = send(voiceId, body);
        if (response == null || response.audio_base64() == null || response.audio_base64().isBlank()) {
            throw new ProviderException(NAME, "EMPTY_RESPONSE", "No audio in the answer", true, null);
        }
        byte[] audio = Base64.getDecoder().decode(response.audio_base64());

        List<WordTiming> words = response.alignment() == null ? List.of()
                : words(response.alignment().characters(),
                response.alignment().character_start_times_seconds(),
                response.alignment().character_end_times_seconds());

        double lastWordEnd = words.isEmpty() ? 0 : words.get(words.size() - 1).endSeconds();
        double duration = Math.max(lastWordEnd, mp3Seconds(audio.length, props.outputFormat()));

        long chars = text.codePointCount(0, text.length());
        BigDecimal cost = props.usdPerThousandChars().multiply(BigDecimal.valueOf(chars))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        ProviderUsage usage = new ProviderUsage(NAME + "/" + props.model(), "characters", chars, 0, cost);
        return new SpeechResult(audio, "audio/mpeg", round(duration), words, usage);
    }

    private Response send(String voiceId, Map<String, Object> body) {
        try {
            return client.post()
                    .uri(b -> b.path("/v1/text-to-speech/{voice}/with-timestamps")
                            .queryParam("output_format", props.outputFormat())
                            .build(voiceId))
                    .header("xi-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errorBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int status = res.getStatusCode().value();
                        if (errorBody.contains("quota_exceeded")) {
                            throw new ProviderException(NAME, "NO_CREDITS",
                                    "ElevenLabs character quota used up", false, null);
                        }
                        throw ProviderException.fromHttpStatus(NAME, status, errorBody);
                    })
                    .body(Response.class);
        } catch (ResourceAccessException e) {
            throw new ProviderException(NAME, "TIMEOUT", e.getMessage(), true, e);
        }
    }

    /**
     * Groups character timings into words: a word is a run of non-space characters.
     * "Hi, there" -> [Hi, 0.00-0.21] [there 0.30-0.62]. Punctuation stays on its word, as in subtitles.
     */
    static List<WordTiming> words(List<String> chars, List<Double> starts, List<Double> ends) {
        List<WordTiming> words = new ArrayList<>();
        if (chars == null || starts == null || ends == null) {
            return words;
        }
        int n = Math.min(chars.size(), Math.min(starts.size(), ends.size()));
        StringBuilder word = new StringBuilder();
        double start = 0;
        double end = 0;
        for (int i = 0; i < n; i++) {
            String c = chars.get(i);
            if (c == null || c.isBlank()) {
                if (!word.isEmpty()) {
                    words.add(new WordTiming(word.toString(), round(start), round(end)));
                    word.setLength(0);
                }
                continue;
            }
            if (word.isEmpty()) {
                start = starts.get(i);
            }
            word.append(c);
            end = ends.get(i);
        }
        if (!word.isEmpty()) {
            words.add(new WordTiming(word.toString(), round(start), round(end)));
        }
        return words;
    }

    /** Length of a constant-bitrate MP3 from its size: mp3_44100_128 -> 128 kbit/s. */
    static double mp3Seconds(int bytes, String outputFormat) {
        String[] parts = outputFormat.split("_");
        if (parts.length < 3 || !parts[0].equals("mp3")) {
            return 0;
        }
        try {
            int kbps = Integer.parseInt(parts[2]);
            return bytes * 8.0 / (kbps * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }

    // ---------- the parts of the answer we read ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String audio_base64, Alignment alignment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alignment(List<String> characters,
                            List<Double> character_start_times_seconds,
                            List<Double> character_end_times_seconds) {
    }
}