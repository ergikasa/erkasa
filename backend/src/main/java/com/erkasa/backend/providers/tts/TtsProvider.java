package com.erkasa.backend.providers.tts;

/** Turns text into speech, with timings per word for subtitles. Mock now, ElevenLabs + Azure in 3.4. */
public interface TtsProvider {

    String name();

    String model();

    SpeechResult synthesize(SpeechRequest request);
}