-- V13: lease, ownership and fencing for RENDER jobs (V2 task A3).
--
-- The backend owns the job. A worker only owns one "dispatch" of it, for a limited time (the lease):
--
--   dispatch   every send to the worker gets a new dispatch_id (and attempt + 1 after the first).
--              The request carries it, every result must carry it back.
--   claim      the worker sends CLAIMED when it starts: lease_owner, claimed_at, lease_until, deadline_at.
--   heartbeat  the worker sends PROGRESS every 20 s; each one moves lease_until forward.
--   fencing    a result counts only if its dispatch_id is the job's current one, the job is RUNNING,
--              and it comes from the lease owner (or the lease is free / expired).
--
-- So an old worker that is still rendering after the job was sent again can never finish the project.
-- The watchdog (task A4) expires leases and enforces the deadlines; these columns are its input.
-- Only the backend writes these columns; the worker only sends messages.

ALTER TABLE jobs
    ADD COLUMN attempt        INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN dispatch_id    UUID,
    ADD COLUMN dispatched_at  TIMESTAMPTZ,
    ADD COLUMN claimed_at     TIMESTAMPTZ,
    ADD COLUMN lease_owner    VARCHAR(100),
    ADD COLUMN lease_until    TIMESTAMPTZ,
    ADD COLUMN heartbeat_at   TIMESTAMPTZ,
    ADD COLUMN deadline_at    TIMESTAMPTZ,
    ADD CONSTRAINT ck_jobs_attempt CHECK (attempt >= 1);

-- At most one active render per project, whatever the code does.
CREATE UNIQUE INDEX uq_jobs_one_active_render ON jobs (project_id)
    WHERE kind = 'RENDER' AND status IN ('QUEUED', 'RUNNING');

-- The watchdog (A4) looks for running renders whose lease or deadline has passed.
CREATE INDEX idx_jobs_render_lease ON jobs (lease_until)
    WHERE kind = 'RENDER' AND status = 'RUNNING';
