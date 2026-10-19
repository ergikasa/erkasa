package com.erkasa.backend.providers.tts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.erkasa.backend.providers.Language;

public interface TtsVoiceRepository extends JpaRepository<TtsVoice, UUID> {

    /** The voice used when the user did not pick one. */
    Optional<TtsVoice> findFirstByLanguageAndIsDefaultTrueAndActiveTrue(Language language);

    /** A voice the user picked; must belong to the video language. */
    Optional<TtsVoice> findFirstByLanguageAndVoiceIdAndActiveTrue(Language language, String voiceId);

    /** Voices to offer in the UI (phase 6). */
    List<TtsVoice> findByLanguageAndActiveTrueOrderByIsDefaultDescDisplayNameAsc(Language language);
}