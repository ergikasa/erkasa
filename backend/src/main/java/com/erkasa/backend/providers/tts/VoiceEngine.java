package com.erkasa.backend.providers.tts;

import com.erkasa.backend.providers.Language;

/**
 * One real voice service (ElevenLabs, Azure). RoutingTtsProvider picks the engine
 * from the tts_voices table and calls it.
 */
public interface VoiceEngine {

    /** Must match tts_voices.provider: "elevenlabs" or "azure". */
    String provider();

    /** false when its API key is missing: calls then fail with NOT_CONFIGURED. */
    boolean configured();

    SpeechResult synthesize(String text, Language language, String voiceId);
}