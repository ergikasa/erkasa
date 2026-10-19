package com.erkasa.backend.providers.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Azure "word boundary" events into WordTiming, kept separate from the Azure SDK so it can
 * be tested without a key.
 *
 * Azure reports words and punctuation as separate events ("Hello" then ","). Subtitles show
 * "Hello," so punctuation is glued to the word before it.
 */
final class AzureWordTimings {

    /** 1 tick = 100 nanoseconds, the unit Azure uses for offsets and durations. */
    static final double TICKS_PER_SECOND = 10_000_000.0;

    /**
     * @param text          the word or punctuation mark
     * @param offsetTicks   when it starts, from the start of the audio
     * @param durationTicks how long it lasts
     * @param punctuation   true for a punctuation event
     */
    record Boundary(String text, long offsetTicks, long durationTicks, boolean punctuation) {
    }

    private AzureWordTimings() {
    }

    static List<WordTiming> toWords(List<Boundary> boundaries) {
        List<WordTiming> words = new ArrayList<>();
        for (Boundary b : boundaries) {
            if (b.text() == null || b.text().isBlank()) {
                continue;
            }
            double start = b.offsetTicks() / TICKS_PER_SECOND;
            double end = (b.offsetTicks() + b.durationTicks()) / TICKS_PER_SECOND;
            if (b.punctuation()) {
                if (!words.isEmpty()) {
                    WordTiming last = words.remove(words.size() - 1);
                    words.add(new WordTiming(last.word() + b.text().strip(), last.startSeconds(),
                            Math.max(last.endSeconds(), round(end))));
                }
                continue;
            }
            words.add(new WordTiming(b.text().strip(), round(start), round(end)));
        }
        return words;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }
}