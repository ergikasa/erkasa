-- V6: one row per call to an AI provider (phase 3).
-- Answers "how much does one video really cost us?" and "which provider fails most?".

CREATE TABLE provider_usage (
    id            UUID PRIMARY KEY,
    kind          VARCHAR(10)    NOT NULL,              -- LLM, IMAGE, TTS
    provider      VARCHAR(50)    NOT NULL,              -- mock, anthropic, fal, elevenlabs, azure
    model         VARCHAR(100),
    unit_type     VARCHAR(20),                          -- tokens, images, characters
    input_units   BIGINT         NOT NULL DEFAULT 0,
    output_units  BIGINT         NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12, 6) NOT NULL DEFAULT 0,
    duration_ms   INTEGER        NOT NULL DEFAULT 0,
    success       BOOLEAN        NOT NULL,
    error_code    VARCHAR(50),
    user_id       UUID           REFERENCES users (id) ON DELETE SET NULL,
    job_id        UUID,                                 -- linked to video jobs in phase 4
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_provider_usage_kind CHECK (kind IN ('LLM', 'IMAGE', 'TTS'))
);

CREATE INDEX idx_provider_usage_created ON provider_usage (created_at);
CREATE INDEX idx_provider_usage_user    ON provider_usage (user_id);
CREATE INDEX idx_provider_usage_job     ON provider_usage (job_id);