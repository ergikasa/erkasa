-- V12: transactional outbox (V2 task A1).
--
-- Problem it fixes: RenderJobService.send() marked the render job RUNNING in a transaction and
-- only then published to RabbitMQ. If RabbitMQ was down at that moment (or the backend died
-- between the commit and the publish), the project stayed RENDERING forever with the credits taken.
--
-- Now a message to RabbitMQ is first written HERE, in the same transaction as the state change.
-- OutboxRelay reads unsent rows every second, publishes them with publisher confirms, and sets
-- sent_at only after RabbitMQ confirmed. Either both the state change and the message exist,
-- or neither does.
--
-- Delivery is "at least once": if the backend dies after RabbitMQ confirmed but before sent_at
-- is committed, the message is sent again. Receivers must cope with duplicates (the render
-- result handling already does; task A3 adds fencing with a dispatch id).

CREATE TABLE outbox_messages (
    id               UUID PRIMARY KEY,
    -- RabbitMQ queue name, published on the default exchange (e.g. render.requests)
    queue            VARCHAR(100) NOT NULL,
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULL = not confirmed by RabbitMQ yet
    sent_at          TIMESTAMPTZ,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    -- after a failed attempt the relay waits (2, 4, 8 ... max 60 s) before trying again
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),

    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- The relay's query: unsent messages that are due, oldest first. Small, because sent rows are
-- excluded and cleaned up after a few days.
CREATE INDEX idx_outbox_unsent ON outbox_messages (next_attempt_at, created_at) WHERE sent_at IS NULL;
-- The cleanup's query.
CREATE INDEX idx_outbox_sent ON outbox_messages (sent_at) WHERE sent_at IS NOT NULL;
