package com.erkasa.backend.providers;



import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageProvider;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.llm.LlmProvider;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.LlmResponse;
import com.erkasa.backend.providers.tts.SpeechRequest;
import com.erkasa.backend.providers.tts.SpeechResult;
import com.erkasa.backend.providers.tts.TtsProvider;

/**
 * The ONLY way the rest of the app calls AI providers.
 *
 * Every call through here gets the same treatment, whichever provider is behind it:
 * - retry with backoff on temporary errors,
 * - timing,
 * - a provider_usage row (success with cost, or failure with an error code).
 *
 * The pipeline (phase 4) uses this class and never knows which provider is configured.
 */
@Service
@EnableConfigurationProperties(ProvidersProperties.class)
public class ProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

    private final LlmProvider llm;
    private final ImageProvider image;
    private final TtsProvider tts;
    private final ProviderUsageRecorder recorder;
    private final ProvidersProperties.RetrySettings retry;

    public ProviderGateway(LlmProvider llm,
                           ImageProvider image,
                           TtsProvider tts,
                           ProviderUsageRecorder recorder,
                           ProvidersProperties props) {
        this.llm = llm;
        this.image = image;
        this.tts = tts;
        this.recorder = recorder;
        this.retry = props.retry();
        log.info("AI providers: llm={}, image={}, tts={}", llm.name(), image.name(), tts.name());
    }

    public LlmResponse generateText(LlmRequest request, CallContext ctx) {
        return call(ProviderKind.LLM, llm.name(), llm.model(), ctx,
                () -> llm.generate(request), LlmResponse::usage);
    }

    public GeneratedImage generateImage(ImageRequest request, CallContext ctx) {
        return call(ProviderKind.IMAGE, image.name(), image.model(), ctx,
                () -> image.generate(request), GeneratedImage::usage);
    }

    public SpeechResult synthesize(SpeechRequest request, CallContext ctx) {
        return call(ProviderKind.TTS, tts.name(), tts.model(), ctx,
                () -> tts.synthesize(request), SpeechResult::usage);
    }

    private <T> T call(ProviderKind kind,
                       String provider,
                       String model,
                       CallContext ctx,
                       Supplier<T> work,
                       java.util.function.Function<T, ProviderUsage> usageOf) {
        long start = System.nanoTime();
        T result;
        try {
            result = Retry.run(retry.maxAttempts(), retry.initialDelay(), work);
        } catch (ProviderException e) {
            safeRecord(ProviderUsageRecord.failure(kind, provider, model, e.getCode(), elapsedMs(start), ctx));
            log.warn("{} call to {} failed: {}", kind, provider, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // A bug or an unexpected error inside a provider: still record it, then rethrow.
            safeRecord(ProviderUsageRecord.failure(kind, provider, model, "UNEXPECTED", elapsedMs(start), ctx));
            throw e;
        }
        safeRecord(ProviderUsageRecord.success(kind, provider, usageOf.apply(result), elapsedMs(start), ctx));
        return result;
    }

    /** Recording the cost must never break the actual work: failures are only logged. */
    private void safeRecord(ProviderUsageRecord record) {
        try {
            recorder.record(record);
        } catch (RuntimeException e) {
            log.error("Could not save provider usage for {}", record.getProvider(), e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}