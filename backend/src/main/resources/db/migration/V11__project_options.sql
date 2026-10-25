-- V11: the choices from the "New video" wizard (phase 9.1).
--
--   purpose          what the user wants to make: EXPLAINER, SHORT_FORM, BUSINESS
--   template         the scene system that draws the video: ANIMATED_2D, INFOGRAPHIC,
--                    CLASSIC (images + motion graphics, how every video was made before phase 9)
--   audience         BEGINNER, INTERMEDIATE, EXPERT
--   tone             EDUCATIONAL, ENTERTAINING, PROFESSIONAL
--   theme            ready-made look until branding arrives: DARK, LIGHT, VIBRANT
--   voice_speed      percent of normal speed: 100 = normal, 90 = slower, 110 = faster
--   caption_style    KARAOKE (word by word), CLEAN, BOLD, NONE (no captions)
--   caption_position BOTTOM, CENTER, TOP
--
-- Existing rows get the defaults, which describe exactly how they were made (CLASSIC, DARK, KARAOKE, BOTTOM).
-- The pipeline starts using these columns in 9.2 (prompts) and 9.5 (voice speed, captions).

ALTER TABLE projects
    ADD COLUMN purpose          VARCHAR(20) NOT NULL DEFAULT 'EXPLAINER',
    ADD COLUMN template         VARCHAR(20) NOT NULL DEFAULT 'CLASSIC',
    ADD COLUMN audience         VARCHAR(15) NOT NULL DEFAULT 'BEGINNER',
    ADD COLUMN tone             VARCHAR(15) NOT NULL DEFAULT 'EDUCATIONAL',
    ADD COLUMN theme            VARCHAR(15) NOT NULL DEFAULT 'DARK',
    ADD COLUMN voice_speed      INTEGER     NOT NULL DEFAULT 100,
    ADD COLUMN caption_style    VARCHAR(15) NOT NULL DEFAULT 'KARAOKE',
    ADD COLUMN caption_position VARCHAR(10) NOT NULL DEFAULT 'BOTTOM';

ALTER TABLE projects
    ADD CONSTRAINT ck_projects_purpose  CHECK (purpose IN ('EXPLAINER', 'SHORT_FORM', 'BUSINESS')),
    ADD CONSTRAINT ck_projects_template CHECK (template IN ('ANIMATED_2D', 'INFOGRAPHIC', 'CLASSIC')),
    ADD CONSTRAINT ck_projects_audience CHECK (audience IN ('BEGINNER', 'INTERMEDIATE', 'EXPERT')),
    ADD CONSTRAINT ck_projects_tone     CHECK (tone IN ('EDUCATIONAL', 'ENTERTAINING', 'PROFESSIONAL')),
    ADD CONSTRAINT ck_projects_theme    CHECK (theme IN ('DARK', 'LIGHT', 'VIBRANT')),
    -- ElevenLabs accepts 0.7x to 1.2x
    ADD CONSTRAINT ck_projects_voice_speed CHECK (voice_speed BETWEEN 70 AND 120),
    ADD CONSTRAINT ck_projects_caption_style    CHECK (caption_style IN ('KARAOKE', 'CLEAN', 'BOLD', 'NONE')),
    ADD CONSTRAINT ck_projects_caption_position CHECK (caption_position IN ('BOTTOM', 'CENTER', 'TOP'));