-- V7: which voice speaks each language, and through which provider (phase 3.4).
-- Changing a language's voice or provider = one UPDATE here, no code change, no redeploy.
--
--   elevenlabs: best quality for EN, DE, ES, FR, IT, RU (model in application.yml)
--   azure:      SQ (ElevenLabs has no Albanian), MK and SR

CREATE TABLE tts_voices (
                            id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            language      VARCHAR(5)   NOT NULL,              -- Language enum: EN, SQ, ...
                            provider      VARCHAR(20)  NOT NULL,              -- elevenlabs, azure
                            voice_id      VARCHAR(100) NOT NULL,              -- ElevenLabs voice id, or Azure voice name
                            display_name  VARCHAR(100) NOT NULL,              -- shown to the user when choosing a voice
                            gender        VARCHAR(10),                        -- male, female
                            is_default    BOOLEAN      NOT NULL DEFAULT false,
                            active        BOOLEAN      NOT NULL DEFAULT true,
                            created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

                            CONSTRAINT ck_tts_voices_provider CHECK (provider IN ('elevenlabs', 'azure')),
                            CONSTRAINT uq_tts_voices UNIQUE (language, provider, voice_id)
);

-- At most one default voice per language.
CREATE UNIQUE INDEX uq_tts_voices_default ON tts_voices (language) WHERE is_default;

-- ElevenLabs premade voices are multilingual: the same voice speaks all six languages.
--   Brian  nPczCjzI2devNBz1zQrb   deep male narrator
--   Sarah  EXAVITQu4vr4xnSDxMaL   clear female voice
INSERT INTO tts_voices (language, provider, voice_id, display_name, gender, is_default)
SELECT lang, 'elevenlabs', v.voice_id, v.display_name, v.gender, v.is_default
FROM (VALUES ('EN'), ('DE'), ('ES'), ('FR'), ('IT'), ('RU')) AS l(lang)
         CROSS JOIN (VALUES
                         ('nPczCjzI2devNBz1zQrb', 'Brian', 'male',   true),
                         ('EXAVITQu4vr4xnSDxMaL', 'Sarah', 'female', false)
) AS v(voice_id, display_name, gender, is_default);

-- Azure neural voices are per language.
INSERT INTO tts_voices (language, provider, voice_id, display_name, gender, is_default) VALUES
                                                                                            ('SQ', 'azure', 'sq-AL-IlirNeural',       'Ilir',       'male',   true),
                                                                                            ('SQ', 'azure', 'sq-AL-AnilaNeural',      'Anila',      'female', false),
                                                                                            ('MK', 'azure', 'mk-MK-AleksandarNeural', 'Aleksandar', 'male',   true),
                                                                                            ('MK', 'azure', 'mk-MK-MarijaNeural',     'Marija',     'female', false),
                                                                                            ('SR', 'azure', 'sr-RS-NicholasNeural',   'Nicholas',   'male',   true),
                                                                                            ('SR', 'azure', 'sr-RS-SophieNeural',     'Sophie',     'female', false);