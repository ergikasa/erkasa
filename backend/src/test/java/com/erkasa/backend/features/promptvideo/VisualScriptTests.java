package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.features.promptvideo.script.ScriptSchema;
import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.features.promptvideo.script.VideoScript.Scene;
import com.erkasa.backend.features.promptvideo.script.Visual;

/** 5.2a: scenes can show motion graphics instead of an AI image. Plain unit tests. */
class VisualScriptTests {

    private static String words(int n) {
        return String.join(" ", Collections.nCopies(n, "word"));
    }

    private static Visual flow(String... steps) {
        return new Visual("flow", "", "", List.of(steps), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "", "");
    }

    private static Visual title(String t) {
        return new Visual("title", t, "Explained", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "", "");
    }

    private static Visual stat(double value, String label) {
        return new Visual("stat", "", "", List.of(), value, "", "x", label, "", "", List.of(), List.of(), "", "", "", "");
    }

    private static Visual code(String code) {
        return new Visual("code", "", "", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), code, "ci.yml", "", "");
    }

    /** 4 scenes x 10 words = 40 words = about 16 s, right for a 15-20 s video. */
    private static VideoScript script(Scene... scenes) {
        return new VideoScript("CI/CD", "upbeat", List.of(scenes));
    }

    @Test
    void graphicScenesNeedNoImagePrompt() {
        VideoScript s = script(
                new Scene(words(10), "", "", title("CI/CD")),
                new Scene(words(10), "", "", flow("Code", "Build", "Test", "Deploy")),
                new Scene(words(10), "", "", stat(200, "more deploys")),
                new Scene(words(10), "", "A server room at night", Visual.image()));

        assertThat(ScriptValidator.validate(s, 16)).isEmpty();
        assertThat(s.scenes()).extracting(Scene::needsImage).containsExactly(false, false, false, true);
    }

    @Test
    void oldScriptsWithoutVisualAreImageScenes() {
        Scene old = new Scene(words(10), "", "A mountain");

        assertThat(old.visual()).isNull();
        assertThat(old.needsImage()).isTrue();
    }

    @Test
    void imageSceneStillNeedsItsPrompt() {
        VideoScript s = script(
                new Scene(words(14), "", "", Visual.image()),
                new Scene(words(13), "", "", flow("A", "B")),
                new Scene(words(13), "", "", flow("A", "B")));

        assertThat(ScriptValidator.validate(s, 16)).anyMatch(p -> p.contains("Scene 1 has no imagePrompt"));
    }

    @Test
    void badVisualsAreExplainedToTheLlm() {
        VideoScript s = script(
                new Scene(words(10), "", "", flow("Only one")),
                new Scene(words(10), "", "", flow("This step has far too many words", "B")),
                new Scene(words(10), "", "", stat(0, "")),
                new Scene(words(10), "", "", code("x".repeat(80))));

        List<String> problems = ScriptValidator.validate(s, 16);

        assertThat(problems).anyMatch(p -> p.startsWith("Scene 1 visual (flow)") && p.contains("2 to 6 steps"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 2 visual (flow)") && p.contains("1 to 4 words"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 3 visual (stat)") && p.contains("positive number"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 3 visual (stat)") && p.contains("label"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 4 visual (code)") && p.contains("48 characters"));
    }

    @Test
    void unknownTypeIsRejected() {
        Visual weird = new Visual("hologram", "", "", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "", "");
        VideoScript s = script(new Scene(words(14), "", "", weird),
                new Scene(words(13), "", "", flow("A", "B")), new Scene(words(13), "", "", flow("A", "B")));

        assertThat(ScriptValidator.validate(s, 16)).anyMatch(p -> p.contains("unknown visual type \"hologram\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAsksForAVisualOnEveryScene() {
        Map<String, Object> scenes = (Map<String, Object>) ((Map<String, Object>) ScriptSchema.schema().get("properties")).get("scenes");
        Map<String, Object> scene = (Map<String, Object>) scenes.get("items");
        Map<String, Object> visual = (Map<String, Object>) ((Map<String, Object>) scene.get("properties")).get("visual");

        assertThat((List<String>) scene.get("required")).contains("visual");
        assertThat(visual.get("additionalProperties")).isEqualTo(false);
        // structured outputs: every field of the flat object is required
        assertThat((List<String>) visual.get("required"))
                .containsExactlyInAnyOrderElementsOf(((Map<String, Object>) visual.get("properties")).keySet());
    }
}
