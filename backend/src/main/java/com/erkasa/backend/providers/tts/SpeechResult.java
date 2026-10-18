package com.erkasa.backend.providers.tts;

import java.util.List;

import com.erkasa.backend.providers.ProviderUsage;

/**
 * @param audio           the audio file bytes
 * @param contentType     e.g. audio/wav or audio/mpeg
 * @param durationSeconds total length of the audio
 * @param words           timing of every word, in order
 */
public record SpeechResult(byte[] audio,
                           String contentType,
                           double durationSeconds,
                           List<WordTiming> words,
                           ProviderUsage usage) {
}