-- V5: one-time tokens sent by email (phase 2, auth).
-- Used for "verify your email" (24 h) and "reset your password" (1 h).
-- Like refresh tokens, only the SHA-256 hash is stored.

CREATE TABLE email_tokens (
                              id          UUID PRIMARY KEY,
                              user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                              purpose     VARCHAR(30)  NOT NULL,
                              token_hash  VARCHAR(64)  NOT NULL,
                              expires_at  TIMESTAMPTZ  NOT NULL,
                              used_at     TIMESTAMPTZ,
                              created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

                              CONSTRAINT uq_email_tokens_hash    UNIQUE (token_hash),
                              CONSTRAINT ck_email_tokens_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD'))
);

CREATE INDEX idx_email_tokens_user_purpose ON email_tokens (user_id, purpose);