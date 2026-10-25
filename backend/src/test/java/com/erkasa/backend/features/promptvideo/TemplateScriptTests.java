package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.features.promptvideo.script.SceneCatalog;
import com.erkasa.backend.features.promptvideo.script.ScriptPrompts;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.ScriptSchema;
import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.features.promptvideo.script.VideoScript.Scene;
import com.erkasa.backend.features.promptvideo.script.Visual;
import com.erkasa.backend.jobs.VideoOptions;
import com.erkasa.backend.jobs.VideoOptions.Audience;
import com.erkasa.backend.jobs.VideoOptions.Purpose;
import com.erkasa.backend.jobs.VideoOptions.Template;
import com.erkasa.backend.jobs.VideoOptions.Tone;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/** 9.2: prompts follow purpose / audience / tone; each template allows only its own scenes. Plain unit tests. */
class TemplateScriptTests {

    // ---------- helpers ----------

    private static VideoOptions options(Purpose purpose, Template template, Audience audience, Tone tone) {
        return VideoOptions.withDefaults(purpose, template, audience, tone, null, null, null, null);
    }

    private static String prompt(VideoOptions o) {
        return ScriptPrompts.system(new ScriptRequest("How CI/CD works", Language.EN, 30, AspectRatio.PORTRAIT, o));
    }

    /** A visual with only the 9.2 fields that matter for a test. */
    private static Visual v(String type, String title, String subtitle, List<String> items, List<Double> values,
                            List<String> labels, List<String> icons, String pose) {
        return new Visual(type, title, subtitle, items, 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "",
                "", values, labels, icons, pose);
    }

    private static String words(int n) {
        return String.join(" ", java.util.Collections.nCopies(n, "word"));
    }

    /** 3 scenes x 13 words = about 16 s, right for a 16-second video. */
    private static VideoScript script(Visual a, Visual b, Visual c) {
        return new VideoScript("T", "calm", List.of(
                new Scene(words(13), "", "", a), new Scene(words(13), "", "", b), new Scene(words(13), "", "", c)));
    }

    private static Visual title() {
        return v("title", "CI/CD", "Explained", List.of(), List.of(), List.of(), List.of(), "");
    }

    private static Visual cta() {
        return new Visual("cta", "", "", List.of(), 0.0, "", "", "", "", "", List.of(), List.of(), "", "", "Follow", "");
    }

    @SuppressWarnings("unchecked")
    private static List<String> visualTypes(Template t) {
        Map<String, Object> scenes = (Map<String, Object>) ((Map<String, Object>) ScriptSchema.schema(t).get("properties")).get("scenes");
        Map<String, Object> scene = (Map<String, Object>) scenes.get("items");
        Map<String, Object> visual = (Map<String, Object>) ((Map<String, Object>) scene.get("properties")).get("visual");
        Map<String, Object> type = (Map<String, Object>) ((Map<String, Object>) visual.get("properties")).get("type");
        return (List<String>) type.get("enum");
    }

    // ---------- prompts ----------

    @Test
    void purposeAudienceAndToneChangeThePrompt() {
        String business = prompt(options(Purpose.BUSINESS, Template.CLASSIC, Audience.EXPERT, Tone.PROFESSIONAL));
        assertThat(business).contains("infographic videos for businesses", "never invent company data",
                "Audience: experts", "Tone: confident, concise and neutral");

        String shortForm = prompt(options(Purpose.SHORT_FORM, Template.CLASSIC, Audience.BEGINNER, Tone.ENTERTAINING));
        assertThat(shortForm).contains("stop the scroll", "Hook in the first 2 seconds", "No jargon", "light humor");
        assertThat(shortForm).doesNotContain("never invent company data");
    }

    @Test
    void onlyClassicTalksAboutImages() {
        assertThat(prompt(options(Purpose.EXPLAINER, Template.CLASSIC, null, null)))
                .contains("\"imagePrompt\": a detailed description", "\"image\":");

        String infographic = prompt(options(Purpose.BUSINESS, Template.INFOGRAPHIC, null, null));
        assertThat(infographic).contains("\"bars\":", "\"timeline\":", "\"cycle\":", "\"iconGrid\":",
                "\"imagePrompt\" is always \"\"", "Icons: use ONLY these names", "chart-bar");
        assertThat(infographic).doesNotContain("\"image\":", "\"character\":", "Poses");

        String animated = prompt(options(Purpose.EXPLAINER, Template.ANIMATED_2D, null, null));
        assertThat(animated).contains("\"character\":", "\"metaphor\":", "\"iconLink\":", "Poses (copied exactly, in English): standing");
        assertThat(animated).doesNotContain("\"image\":", "\"bars\":", "\"code\":");
    }

    @Test
    void oldRequestsStayClassic() {
        ScriptRequest old = new ScriptRequest("idea", Language.EN, 30, AspectRatio.PORTRAIT);
        assertThat(old.template()).isEqualTo(Template.CLASSIC);
        assertThat(old.options()).isEqualTo(VideoOptions.classic());
    }

    // ---------- schema and catalog ----------

    @Test
    void schemaAllowsOnlyTheTemplatesScenes() {
        assertThat(visualTypes(Template.CLASSIC)).contains("image", "code").doesNotContain("bars", "character");
        assertThat(visualTypes(Template.INFOGRAPHIC)).contains("bars", "timeline", "cycle", "iconGrid").doesNotContain("image");
        assertThat(visualTypes(Template.ANIMATED_2D)).contains("character", "metaphor", "zoom").doesNotContain("image");
        // every catalog type is a known visual type
        for (Template t : Template.values()) {
            assertThat(Visual.TYPES).containsAll(SceneCatalog.types(t));
        }
        assertThat(SceneCatalog.ICONS).doesNotHaveDuplicates();
    }

    @Test
    void templatesThatAreNotReadyFallBackToClassic() {
        assertThat(SceneCatalog.effective(Template.CLASSIC)).isEqualTo(Template.CLASSIC);
        for (Template t : Template.values()) {
            Template expected = SceneCatalog.READY.contains(t) ? t : Template.CLASSIC;
            assertThat(SceneCatalog.effective(t)).isEqualTo(expected);
        }
    }

    // ---------- validation ----------

    @Test
    void aGoodInfographicScriptPasses() {
        Visual bars = v("bars", "EV sales", "", List.of("2020", "2023", "2026"), List.of(3.0, 15.0, 25.0),
                List.of(), List.of(), "");
        Visual timeline = v("timeline", "", "", List.of("Order placed", "Packed", "Delivered"), List.of(),
                List.of("Day 1", "Day 2", "Day 3"), List.of(), "");
        assertThat(ScriptValidator.validate(script(title(), bars, timeline), 16, Template.INFOGRAPHIC)).isEmpty();

        Visual grid = v("iconGrid", "", "", List.of("Fast", "Safe", "Cheap"), List.of(), List.of(),
                List.of("zap", "shield-check", "coins"), "");
        Visual cycle = v("cycle", "Water", "", List.of("Rain", "River", "Sea", "Cloud"), List.of(), List.of(), List.of(), "");
        assertThat(ScriptValidator.validate(script(grid, cycle, cta()), 16, Template.INFOGRAPHIC)).isEmpty();
    }

    @Test
    void aGoodAnimatedScriptPasses() {
        Visual character = v("character", "", "", List.of(), List.of(), List.of(), List.of("circle-help"), "thinking");
        Visual metaphor = v("metaphor", "An API", "like a restaurant waiter", List.of(), List.of(), List.of(),
                List.of("server", "utensils"), "");
        Visual link = v("iconLink", "", "", List.of("App", "API", "Database"), List.of(), List.of(),
                List.of("smartphone", "server", "database"), "");
        assertThat(ScriptValidator.validate(script(character, metaphor, link), 16, Template.ANIMATED_2D)).isEmpty();
    }

    @Test
    void scenesFromAnotherTemplateAreRefused() {
        VideoScript withImage = script(title(), Visual.image(), cta());
        assertThat(ScriptValidator.validate(withImage, 16, Template.INFOGRAPHIC))
                .anyMatch(p -> p.contains("Scene 2 uses \"image\", which this video style does not have"));

        Visual zoom = v("zoom", "Engine", "", List.of("Pistons", "Valves"), List.of(), List.of(), List.of("car"), "");
        assertThat(ScriptValidator.validate(script(title(), zoom, cta()), 16, Template.CLASSIC))
                .anyMatch(p -> p.contains("Scene 2 uses \"zoom\""));
        // old scripts (no visual = image) are still fine for CLASSIC
        assertThat(ScriptValidator.validate(script(title(), null, cta()), 16)).noneMatch(p -> p.contains("uses \"image\""));
    }

    @Test
    void badNewScenesAreExplainedToTheLlm() {
        Visual bars = v("bars", "Sales", "", List.of("A", "B", "C"), List.of(1.0, 2.0), List.of(), List.of(), "");
        Visual grid = v("iconGrid", "", "", List.of("Fast", "Safe", "Cheap"), List.of(), List.of(),
                List.of("zap", "unicorn", "coins"), "");
        Visual timeline = v("timeline", "", "", List.of("Start", "Middle", "End"), List.of(), List.of("2020"), List.of(), "");
        List<String> problems = ScriptValidator.validate(script(bars, grid, timeline), 16, Template.INFOGRAPHIC);

        assertThat(problems).anyMatch(p -> p.startsWith("Scene 1 visual (bars)") && p.contains("one number per item"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 2 visual (iconGrid)") && p.contains("unknown icons [unicorn]"));
        assertThat(problems).anyMatch(p -> p.startsWith("Scene 3 visual (timeline)") && p.contains("one date or step per item"));

        Visual character = v("character", "", "", List.of(), List.of(), List.of(), List.of(), "dancing");
        Visual metaphor = v("metaphor", "An API", "like a waiter", List.of(), List.of(), List.of(), List.of("server"), "");
        List<String> animated = ScriptValidator.validate(script(character, metaphor, cta()), 16, Template.ANIMATED_2D);
        assertThat(animated).anyMatch(p -> p.startsWith("Scene 1 visual (character)") && p.contains("pose must be one of"));
        assertThat(animated).anyMatch(p -> p.startsWith("Scene 2 visual (metaphor)") && p.contains("exactly 2 icons"));
    }
}
