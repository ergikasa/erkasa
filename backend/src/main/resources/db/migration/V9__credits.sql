-- V9: credits (phase 7). 1 credit = 1 second of video.
--
-- users.credits is the balance (fast to read and to lock); credit_transactions is the history
-- (every change, with the balance after it), so a balance can always be explained.

ALTER TABLE users
    ADD COLUMN credits INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_credits CHECK (credits >= 0);

CREATE TABLE credit_transactions (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- the video it was for (charges and refunds), else NULL
    project_id    UUID         REFERENCES projects (id) ON DELETE SET NULL,
    -- + adds credits, - takes them
    amount        INTEGER      NOT NULL,
    balance_after INTEGER      NOT NULL,
    reason        VARCHAR(30)  NOT NULL,
    note          VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_credit_tx_amount CHECK (amount <> 0),
    CONSTRAINT ck_credit_tx_balance CHECK (balance_after >= 0),
    CONSTRAINT ck_credit_tx_reason CHECK (reason IN ('SIGNUP_BONUS', 'VIDEO_CHARGE', 'VIDEO_REFUND', 'ADMIN_GRANT'))
);

CREATE INDEX ix_credit_tx_user ON credit_transactions (user_id, created_at DESC);
CREATE INDEX ix_credit_tx_project ON credit_transactions (project_id);

-- Accounts that existed before credits get the same welcome bonus as new ones.
INSERT INTO credit_transactions (id, user_id, amount, balance_after, reason, note)
SELECT gen_random_uuid(), id, 30, 30, 'SIGNUP_BONUS', 'Welcome credits (account created before credits existed)'
FROM users;

UPDATE users SET credits = 30;
