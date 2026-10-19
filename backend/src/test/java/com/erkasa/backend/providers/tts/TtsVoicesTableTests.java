package com.erkasa.backend.providers.tts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.providers.Language;

/** V7 seeds a default voice for every video language, on the provider we decided. */
class TtsVoicesTableTests extends AbstractIntegrationTest {

    private static final Set<Language> AZURE = Set.of(Language.SQ, Language.MK, Language.SR);

    @Autowired
    private TtsVoiceRepository voices;

    @Test
    void everyLanguageHasADefaultVoiceOnTheRightProvider() {
        for (Language language : Language.values()) {
            TtsVoice voice = voices.findFirstByLanguageAndIsDefaultTrueAndActiveTrue(language).orElseThrow(
                    () -> new AssertionError("No default voice for " + language));
            assertThat(voice.getProvider())
                    .as("provider for %s", language)
                    .isEqualTo(AZURE.contains(language) ? "azure" : "elevenlabs");
        }
    }

    @Test
    void everyLanguageOffersAMaleAndAFemaleVoice() {
        for (Language language : Language.values()) {
            assertThat(voices.findByLanguageAndActiveTrueOrderByIsDefaultDescDisplayNameAsc(language))
                    .as("voices for %s", language)
                    .extracting(TtsVoice::getGender)
                    .containsExactlyInAnyOrder("male", "female");
        }
    }

    @Test
    void azureVoicesBelongToTheirLanguage() {
        assertThat(voices.findFirstByLanguageAndVoiceIdAndActiveTrue(Language.SQ, "sq-AL-AnilaNeural")).isPresent();
        assertThat(voices.findFirstByLanguageAndVoiceIdAndActiveTrue(Language.EN, "sq-AL-AnilaNeural")).isEmpty();
    }
}