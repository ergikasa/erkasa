-- V10: payments with Paddle (phase 8). One row per Paddle transaction that bought credits.

CREATE TABLE payments (
    id                      UUID PRIMARY KEY,
    -- txn_... : Paddle sends a webhook more than once; the unique index makes it count once
    paddle_transaction_id   VARCHAR(64)  NOT NULL,
    user_id                 UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pack                    VARCHAR(30)  NOT NULL,
    credits                 INTEGER      NOT NULL,
    -- what the customer paid, in the smallest unit (cents), as Paddle reports it
    amount_minor            BIGINT       NOT NULL,
    currency                VARCHAR(3)   NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    -- credits taken back after a refund / chargeback
    credits_taken_back      INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_payments_paddle_txn UNIQUE (paddle_transaction_id),
    CONSTRAINT ck_payments_credits CHECK (credits > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('COMPLETED', 'REFUNDED'))
);

CREATE INDEX ix_payments_user ON payments (user_id, created_at DESC);

CREATE TRIGGER trg_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- two new reasons in the credits history
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_tx_reason;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_tx_reason
    CHECK (reason IN ('SIGNUP_BONUS', 'VIDEO_CHARGE', 'VIDEO_REFUND', 'ADMIN_GRANT', 'PURCHASE', 'PURCHASE_REFUND'));
