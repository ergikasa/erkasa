package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.features.promptvideo.script.ScriptPrompts;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.ScriptSchema;
import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.SoundCue;
import com.erkasa.backend.features.promptvideo.script.SoundEffects;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.features.promptvideo.script.VideoScript.Scene;
import com.erkasa.backend.features.promptvideo.script.Visual;

import tools.jackson.databind.json.JsonMapper;

/** 5.6: the LLM may add 0-2 sound effects per scene from a fixed library. Plain unit tests. */
class SoundEffectsScriptTests {

    private static String words(int n) {
        return String.join(" ", Collections.nCopies(n, "money"));
    }

    private static Scene scene(SoundCue... sfx) {
        return new Scene(words(10), "", "A city at night", Visual.image(), List.of(sfx));
    }

    /** 4 scenes x 10 words = about 16 s. */
    private static VideoScript script(Scene first) {
        return new VideoScript("Money", "upbeat", List.of(first, scene(), scene(), scene()));
    }

    @Test
    void theLibraryHasTwentySounds() {
        assertThat(SoundEffects.SOUNDS).hasSize(20).contains("whoosh", "pop", "ding", "cash", "typing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaAllowsOnlyLibrarySounds() {
        Map<String, Object> scenes = (Map<String, Object>) ((Map<String, Object>) ScriptSchema.schema().get("properties")).get("scenes");
        Map<String, Object> scene = (Map<String, Object>) scenes.get("items");
        assertThat((List<String>) scene.get("required")).contains("sfx");
        Map<String, Object> sfx = (Map<String, Object>) ((Map<String, Object>) scene.get("properties")).get("sfx");
        Map<String, Object> cue = (Map<String, Object>) sfx.get("items");
        Map<String, Object> sound = (Map<String, Object>) ((Map<String, Object>) cue.get("properties")).get("sound");
        assertThat((List<String>) sound.get("enum")).isEqualTo(SoundEffects.SOUNDS);
        assertThat(cue.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void upToTwoKnownSoundsAreFine() {
        VideoScript s = script(scene(new SoundCue("cash", "money"), new SoundCue("ding", "")));
        assertThat(ScriptValidator.validate(s, 16)).isEmpty();
    }

    @Test
    void anOnWordThatIsNotSpokenIsNotAnError() {
        // the worker then plays it when the scene starts; no paid LLM retry for that
        assertThat(ScriptValidator.validate(script(scene(new SoundCue("ding", "banana"))), 16)).isEmpty();
    }

    @Test
    void tooManyOrUnknownSoundsAreReported() {
        VideoScript tooMany = script(scene(new SoundCue("cash", ""), new SoundCue("ding", ""), new SoundCue("pop", "")));
        assertThat(ScriptValidator.validate(tooMany, 16)).anyMatch(p -> p.contains("Scene 1 has 3 sound effects"));

        VideoScript unknown = script(scene(new SoundCue("explosion", "")));
        assertThat(ScriptValidator.validate(unknown, 16)).anyMatch(p -> p.contains("unknown sound effect \"explosion\""));
    }

    @Test
    void scriptsSavedBeforeSoundEffectsStillLoad() {
        String old = """
                {"title":"Old","musicMood":"calm","scenes":[
                  {"narration":"Hello there","onScreenText":"","imagePrompt":"A lake"}]}
                """;
        VideoScript s = JsonMapper.builder().build().readValue(old, VideoScript.class);
        assertThat(s.scenes().get(0).sfx()).isEmpty();
        assertThat(new Scene("a", "", "b").sfx()).isEmpty();
    }

    @Test
    void thePromptListsEverySound() {
        String system = ScriptPrompts.system(new ScriptRequest("Money tips", com.erkasa.backend.providers.Language.EN, 30,
                com.erkasa.backend.providers.AspectRatio.PORTRAIT));
        assertThat(system).contains("\"sfx\"").contains("cash (money earned, a sale, a price)").contains("boing (");
    }
}
