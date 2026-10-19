package com.erkasa.backend.providers.tts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderUsage;
import com.microsoft.cognitiveservices.speech.CancellationErrorCode;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisBoundaryType;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;

/**
 * Azure neural voices through the official Speech SDK. Used for SQ, MK and SR.
 *
 * Why the SDK and not plain HTTP: Azure's REST endpoint returns only audio, while the SDK
 * also sends a "word boundary" event for every word, which the subtitles need.
 *
 * Docs: https://learn.microsoft.com/azure/ai-services/speech-service/how-to-speech-synthesis
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "tts", havingValue = "elevenlabs-azure")
@EnableConfigurationProperties(AzureSpeechProperties.class)
public class AzureEngine implements VoiceEngine {

    static final String NAME = "azure";
    private static final Logger log = LoggerFactory.getLogger(AzureEngine.class);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    /** Constant bitrate of the format below, used to compute the duration from the file size. */
    private static final int KBPS = 96;

    private final AzureSpeechProperties props;

    public AzureEngine(AzureSpeechProperties props) {
        this.props = props;
        if (!configured()) {
            log.warn("AZURE_SPEECH_KEY is not set: voices with provider=azure will fail");
        }
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return props.key() != null && !props.key().isBlank();
    }

    @Override
    public SpeechResult synthesize(String text, Language language, String voiceId) {
        if (!configured()) {
            throw new ProviderException(NAME, "NOT_CONFIGURED", "AZURE_SPEECH_KEY is not set", false, null);
        }

        List<AzureWordTimings.Boundary> boundaries = Collections.synchronizedList(new ArrayList<>());
        byte[] audio;

        try (SpeechConfig config = SpeechConfig.fromSubscription(props.key(), props.region())) {
            config.setSpeechSynthesisVoiceName(voiceId);
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio24Khz96KBitRateMonoMp3);

            // null AudioConfig = keep the audio in memory, do not play it on a speaker.
            try (SpeechSynthesizer synthesizer = new SpeechSynthesizer(config, (AudioConfig) null)) {
                synthesizer.WordBoundary.addEventListener((sender, e) -> {
                    if (e.getBoundaryType() == SpeechSynthesisBoundaryType.Sentence) {
                        return;
                    }
                    boundaries.add(new AzureWordTimings.Boundary(
                            e.getText(),
                            e.getAudioOffset(),
                            e.getDuration(),
                            e.getBoundaryType() == SpeechSynthesisBoundaryType.Punctuation));
                });

                try (SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text)
                        .get(props.timeout().toSeconds(), TimeUnit.SECONDS)) {
                    if (result.getReason() == ResultReason.Canceled) {
                        throw canceled(SpeechSynthesisCancellationDetails.fromResult(result));
                    }
                    audio = result.getAudioData();
                }
            }
        } catch (TimeoutException e) {
            throw new ProviderException(NAME, "TIMEOUT", "No answer within " + props.timeout(), true, e);
        } catch (ExecutionException e) {
            throw new ProviderException(NAME, "PROVIDER_ERROR", String.valueOf(e.getCause()), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(NAME, "INTERRUPTED", "Interrupted", false, e);
        }

        if (audio == null || audio.length == 0) {
            throw new ProviderException(NAME, "EMPTY_RESPONSE", "No audio in the answer", true, null);
        }

        List<WordTiming> words = AzureWordTimings.toWords(new ArrayList<>(boundaries));
        double lastWordEnd = words.isEmpty() ? 0 : words.get(words.size() - 1).endSeconds();
        double duration = Math.max(lastWordEnd, audio.length * 8.0 / (KBPS * 1000.0));

        long chars = text.codePointCount(0, text.length());
        BigDecimal cost = props.usdPerThousandChars().multiply(BigDecimal.valueOf(chars))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        ProviderUsage usage = new ProviderUsage(NAME + "/" + voiceId, "characters", chars, 0, cost);
        return new SpeechResult(audio, "audio/mpeg", Math.round(duration * 1000) / 1000.0, words, usage);
    }

    private static ProviderException canceled(SpeechSynthesisCancellationDetails details) {
        CancellationErrorCode code = details.getErrorCode();
        String message = details.getErrorDetails();
        return switch (code) {
            case AuthenticationFailure, Forbidden ->
                    new ProviderException(NAME, "AUTH_FAILED", message, false, null);
            case BadRequest ->
                    new ProviderException(NAME, "BAD_REQUEST", message, false, null);
            case TooManyRequests ->
                    new ProviderException(NAME, "RATE_LIMITED", message, true, null);
            case ServiceTimeout ->
                    new ProviderException(NAME, "TIMEOUT", message, true, null);
            default ->
                    new ProviderException(NAME, "PROVIDER_ERROR", code + " " + message, true, null);
        };
    }
}