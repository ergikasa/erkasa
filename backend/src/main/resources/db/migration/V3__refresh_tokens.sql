-- V3: refresh tokens (phase 2, auth).
-- We never store the token itself, only its SHA-256 hash: a stolen database dump
-- cannot be used to log in as anyone.

CREATE TABLE refresh_tokens (
                                id           UUID PRIMARY KEY,
                                user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                                token_hash   VARCHAR(64)  NOT NULL,
    -- All tokens created from one login share a family (used for rotation in 2.4).
                                family_id    UUID         NOT NULL,
                                expires_at   TIMESTAMPTZ  NOT NULL,
                                created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                revoked_at   TIMESTAMPTZ,
                                replaced_by  UUID,

                                CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);