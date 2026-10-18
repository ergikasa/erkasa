package com.erkasa.backend.providers.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.erkasa.backend.providers.ProviderUsage;

/**
 * Free, offline stand-in for a real voice. Active while erkasa.providers.tts=mock.
 *
 * Produces a real, playable WAV file: silence with a short "tick" at the start of every word,
 * plus the timing of each word. Longer words take longer; punctuation adds a pause.
 * That is enough to build and test subtitles and video timing before paying for real voices.
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "tts", havingValue = "mock", matchIfMissing = true)
public class MockTtsProvider implements TtsProvider {

    private static final int SAMPLE_RATE = 22_050;
    private static final double LEAD_IN = 0.3;
    private static final double TAIL = 0.3;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-tts";
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        List<WordTiming> words = timeWords(text);
        double duration = (words.isEmpty() ? LEAD_IN : words.get(words.size() - 1).endSeconds()) + TAIL;

        byte[] wav = toWav(ticks(words, duration));
        return new SpeechResult(wav, "audio/wav", duration, words,
                ProviderUsage.free(model(), "characters", text.length(), 0));
    }

    private static List<WordTiming> timeWords(String text) {
        List<WordTiming> words = new ArrayList<>();
        if (text.isEmpty()) {
            return words;
        }
        double t = LEAD_IN;
        for (String word : text.split("\\s+")) {
            long letters = word.codePoints().filter(Character::isLetterOrDigit).count();
            double length = 0.15 + 0.055 * letters;
            words.add(new WordTiming(word, round(t), round(t + length)));
            t += length + 0.05;
            if (word.matches(".*[.!?;:,]$")) {
                t += word.endsWith(",") ? 0.15 : 0.3;
            }
        }
        return words;
    }

    /** Silence with a 40 ms soft 880 Hz beep at the start of each word. */
    private static short[] ticks(List<WordTiming> words, double durationSeconds) {
        short[] samples = new short[(int) Math.ceil(durationSeconds * SAMPLE_RATE)];
        int tickLength = (int) (0.04 * SAMPLE_RATE);
        for (WordTiming word : words) {
            int start = (int) (word.startSeconds() * SAMPLE_RATE);
            for (int i = 0; i < tickLength && start + i < samples.length; i++) {
                double fade = 1.0 - (double) i / tickLength;
                samples[start + i] = (short) (3000 * fade * Math.sin(2 * Math.PI * 880 * i / SAMPLE_RATE));
            }
        }
        return samples;
    }

    /** 16-bit mono PCM WAV: a 44-byte header followed by the samples. */
    private static byte[] toWav(short[] samples) {
        int dataLength = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataLength);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);                    // size of the fmt block
        b.putShort((short) 1);           // PCM
        b.putShort((short) 1);           // mono
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * 2);       // bytes per second
        b.putShort((short) 2);           // bytes per sample
        b.putShort((short) 16);          // bits per sample
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataLength);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }
}