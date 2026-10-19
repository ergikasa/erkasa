package com.erkasa.backend.providers.tts;

import java.util.UUID;

import com.erkasa.backend.providers.Language;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row of tts_voices (see V7__tts_voices.sql): a voice for a language and the provider that speaks it. */
@Entity
@Table(name = "tts_voices")
public class TtsVoice {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Language language;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "voice_id", nullable = false, length = 100)
    private String voiceId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 10)
    private String gender;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private boolean active;

    protected TtsVoice() {
    }

    /** For tests. */
    public TtsVoice(Language language, String provider, String voiceId, String displayName, boolean isDefault) {
        this.id = UUID.randomUUID();
        this.language = language;
        this.provider = provider;
        this.voiceId = voiceId;
        this.displayName = displayName;
        this.isDefault = isDefault;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public Language getLanguage() {
        return language;
    }

    public String getProvider() {
        return provider;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getGender() {
        return gender;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public boolean isActive() {
        return active;
    }
}