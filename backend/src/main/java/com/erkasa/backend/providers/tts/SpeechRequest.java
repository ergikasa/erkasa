package com.erkasa.backend.providers.tts;

import com.erkasa.backend.providers.Language;

/**
 * @param text     what to say
 * @param language the video language
 * @param voiceId  a specific voice, or null for the default voice of that language
 */
public record SpeechRequest(String text, Language language, String voiceId) {
}