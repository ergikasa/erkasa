package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.erkasa.backend.features.promptvideo.script.ScriptGenerator;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderGateway;
import com.erkasa.backend.providers.ProviderUsage;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.LlmResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test of the generate -> validate -> retry loop. The gateway is a Mockito mock that
 * returns whatever JSON we want, so we can simulate a bad first answer.
 */
class ScriptGeneratorTests {

    private static final ScriptRequest REQUEST =
            new ScriptRequest("Why the sky is blue", Language.EN, 30, AspectRatio.PORTRAIT);

    private final ProviderGateway gateway = mock(ProviderGateway.class);
    private final ScriptGenerator generator = new ScriptGenerator(gateway, JsonMapper.builder().build());

    private static LlmResponse llm(String json) {
        return new LlmResponse(json, ProviderUsage.free("test", "tokens", 1, 1));
    }

    /** A valid 30-second script: 4 scenes x 16 words = 64 words, about 26 s. */
    private static String goodScript() {
        String narration = "The sky looks blue because air scatters short blue light waves more than red ones today";
        StringBuilder sb = new StringBuilder("{\"title\":\"Blue sky\",\"musicMood\":\"calm\",\"scenes\":[");
        for (int i = 1; i <= 4; i++) {
            sb.append(i > 1 ? "," : "")
                    .append("{\"narration\":\"").append(narration)
                    .append("\",\"onScreenText\":\"Light scatters\",\"imagePrompt\":\"Blue sky over a field\"}");
        }
        return sb.append("]}").toString();
    }

    private static final String TWO_SCENES = """
            {"title":"Blue sky","musicMood":"calm","scenes":[
              {"narration":"Short one.","onScreenText":"","imagePrompt":"sky"},
              {"narration":"Short two.","onScreenText":"","imagePrompt":"sky"}]}
            """;

    @Test
    void aValidFirstAnswerIsReturnedDirectly() {
        when(gateway.generateText(any(), any())).thenReturn(llm(goodScript()));

        VideoScript script = generator.generate(REQUEST, CallContext.none());

        assertThat(script.scenes()).hasSize(4);
        assertThat(script.musicMood()).isEqualTo("calm");
        verify(gateway, times(1)).generateText(any(), any());
    }

    @Test
    void theRequestUsesTheScriptSchema() {
        when(gateway.generateText(any(), any())).thenReturn(llm(goodScript()));
        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);

        generator.generate(REQUEST, CallContext.none());

        verify(gateway).generateText(sent.capture(), any());
        assertThat(sent.getValue().jsonSchema()).containsKey("properties");
        assertThat(sent.getValue().systemPrompt()).contains("English", "30 seconds");
        assertThat(sent.getValue().userPrompt()).contains("Why the sky is blue");
    }

    @Test
    void aBadFirstAnswerIsRetriedWithTheProblemsListed() {
        when(gateway.generateText(any(), any())).thenReturn(llm(TWO_SCENES), llm(goodScript()));
        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);

        VideoScript script = generator.generate(REQUEST, CallContext.none());

        assertThat(script.scenes()).hasSize(4);
        verify(gateway, times(2)).generateText(sent.capture(), any());
        List<LlmRequest> requests = sent.getAllValues();
        assertThat(requests.get(1).userPrompt()).contains("previous script had these problems", "2 scenes");
    }

    @Test
    void invalidJsonIsRetried() {
        when(gateway.generateText(any(), any())).thenReturn(llm("not json at all"), llm(goodScript()));

        assertThat(generator.generate(REQUEST, CallContext.none()).scenes()).hasSize(4);
    }

    @Test
    void givesUpAfterTwoBadAnswers() {
        when(gateway.generateText(any(), any())).thenReturn(llm(TWO_SCENES));

        assertThatThrownBy(() -> generator.generate(REQUEST, CallContext.none()))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_SCRIPT"));
        verify(gateway, times(2)).generateText(any(), any());
    }
}