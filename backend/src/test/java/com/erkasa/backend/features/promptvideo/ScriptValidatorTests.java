package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.features.promptvideo.script.VideoScript.Scene;

/** Plain unit tests: the rules the JSON schema cannot express. */
class ScriptValidatorTests {

    private static String words(int n) {
        return String.join(" ", Collections.nCopies(n, "word"));
    }

    /** A script of `scenes` scenes, each with `wordsPerScene` words of narration. */
    private static VideoScript script(int scenes, int wordsPerScene) {
        List<Scene> list = new ArrayList<>();
        for (int i = 0; i < scenes; i++) {
            list.add(new Scene(words(wordsPerScene), "Key idea", "A mountain at sunrise"));
        }
        return new VideoScript("Title", "calm", list);
    }

    @Test
    void aGoodScriptHasNoProblems() {
        // 6 scenes x 17 words = 102 words = about 41 s for a 45 s video.
        assertThat(ScriptValidator.validate(script(6, 17), 45)).isEmpty();
    }

    @Test
    void tooFewScenes() {
        assertThat(ScriptValidator.validate(script(2, 40), 45)).anyMatch(p -> p.contains("2 scenes"));
    }

    @Test
    void narrationTooLongForTheDuration() {
        // 6 x 40 = 240 words = about 96 s for a 45 s video.
        assertThat(ScriptValidator.validate(script(6, 40), 45)).anyMatch(p -> p.contains("too long"));
    }

    @Test
    void narrationTooShortForTheDuration() {
        // 3 x 3 = 9 words = about 4 s for a 60 s video.
        assertThat(ScriptValidator.validate(script(3, 3), 60)).anyMatch(p -> p.contains("too short"));
    }

    @Test
    void emptyFieldsAndBadMoodAreReported() {
        VideoScript bad = new VideoScript("", "angry", List.of(
                new Scene("", "ok", "img"),
                new Scene(words(20), words(12), ""),
                new Scene(words(20), "", "img")));

        List<String> problems = ScriptValidator.validate(bad, 20);

        assertThat(problems)
                .anyMatch(p -> p.contains("title"))
                .anyMatch(p -> p.contains("musicMood"))
                .anyMatch(p -> p.contains("Scene 1 has no narration"))
                .anyMatch(p -> p.contains("Scene 2 has no imagePrompt"))
                .anyMatch(p -> p.contains("Scene 2 onScreenText is too long"));
    }

    @Test
    void wordTargetsFollowTheSpeakingPace() {
        // 2.5 words per second: 60 s -> about 135 words, at most 165.
        assertThat(ScriptValidator.targetWords(60)).isEqualTo(135);
        assertThat(ScriptValidator.maxWords(60)).isEqualTo(165);
    }
}