package com.erkasa.backend.providers.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderUsage;

/** The router picks the voice from tts_voices and the engine from the voice's provider. */
class RoutingTtsProviderTests {

    /** Remembers what it was asked to say instead of calling a real service. */
    static final class FakeEngine implements VoiceEngine {
        private final String provider;
        final List<String> calls = new ArrayList<>();

        FakeEngine(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public SpeechResult synthesize(String text, Language language, String voiceId) {
            calls.add(language + "|" + voiceId + "|" + text);
            return new SpeechResult(new byte[] {1}, "audio/mpeg", 1.0, List.of(),
                    ProviderUsage.free(provider + "/" + voiceId, "characters", text.length(), 0));
        }
    }

    private final TtsVoiceRepository voices = mock(TtsVoiceRepository.class);
    private final FakeEngine eleven = new FakeEngine("elevenlabs");
    private final FakeEngine azure = new FakeEngine("azure");
    private RoutingTtsProvider router;

    @BeforeEach
    void setUp() {
        router = new RoutingTtsProvider(voices, List.of(eleven, azure));
        when(voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(Language.EN))
                .thenReturn(Optional.of(new TtsVoice(Language.EN, "elevenlabs", "brian", "Brian", true)));
        when(voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(Language.SQ))
                .thenReturn(Optional.of(new TtsVoice(Language.SQ, "azure", "sq-AL-IlirNeural", "Ilir", true)));
        when(voices.findFirstByLanguageAndVoiceIdAndActiveTrue(Language.SQ, "sq-AL-AnilaNeural"))
                .thenReturn(Optional.of(new TtsVoice(Language.SQ, "azure", "sq-AL-AnilaNeural", "Anila", false)));
    }

    @Test
    void englishGoesToElevenLabsWithTheDefaultVoice() {
        SpeechResult result = router.synthesize(new SpeechRequest("  Hello world ", Language.EN, null));

        assertThat(eleven.calls).containsExactly("EN|brian|Hello world");
        assertThat(azure.calls).isEmpty();
        assertThat(result.usage().model()).isEqualTo("elevenlabs/brian");
    }

    @Test
    void albanianGoesToAzure() {
        router.synthesize(new SpeechRequest("Mirëdita", Language.SQ, null));

        assertThat(azure.calls).containsExactly("SQ|sq-AL-IlirNeural|Mirëdita");
        assertThat(eleven.calls).isEmpty();
    }

    @Test
    void aPickedVoiceIsUsed() {
        router.synthesize(new SpeechRequest("Mirëdita", Language.SQ, "sq-AL-AnilaNeural"));

        assertThat(azure.calls).containsExactly("SQ|sq-AL-AnilaNeural|Mirëdita");
    }

    @Test
    void aVoiceFromAnotherLanguageIsRejected() {
        when(voices.findFirstByLanguageAndVoiceIdAndActiveTrue(Language.SQ, "brian")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> router.synthesize(new SpeechRequest("Mirëdita", Language.SQ, "brian")))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UNKNOWN_VOICE");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void languageWithoutDefaultVoiceIsReported() {
        when(voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(Language.DE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> router.synthesize(new SpeechRequest("Hallo", Language.DE, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.getCode()).isEqualTo("NO_VOICE"));
    }

    @Test
    void emptyTextIsRejected() {
        assertThatThrownBy(() -> router.synthesize(new SpeechRequest("  ", Language.EN, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_REQUEST"));
    }

    @Test
    void voiceWithUnknownProviderIsReported() {
        when(voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(Language.FR))
                .thenReturn(Optional.of(new TtsVoice(Language.FR, "polly", "x", "X", true)));

        assertThatThrownBy(() -> router.synthesize(new SpeechRequest("Bonjour", Language.FR, null)))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.getCode()).isEqualTo("NO_ENGINE"));
    }
}