package com.erkasa.backend.providers.tts;

/**
 * When one word is spoken. Subtitles use this to highlight each word exactly as it is heard.
 *
 * @param word         the word as written
 * @param startSeconds from the start of the audio
 * @param endSeconds   from the start of the audio
 */
public record WordTiming(String word, double startSeconds, double endSeconds) {
}