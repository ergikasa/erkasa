-- V8: video projects and the pipeline that builds them (phase 4).
--
--   project  = one video a user is making (idea, language, script, final result)
--   job      = one run of one stage for a project: SCRIPT, ASSETS (images + voices), RENDER (phase 5)
--   job_step = one unit of paid work inside a job: the script, or the image / voice of ONE scene
--
-- Why steps: if scene 4 of 8 fails, we retry only scene 4. Scenes 1-3 are SUCCEEDED and are
-- never generated (or paid for) again.

CREATE TABLE projects (
                          id                UUID PRIMARY KEY,
                          user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                          idea              VARCHAR(1000) NOT NULL,
                          language          VARCHAR(5)   NOT NULL,              -- Language enum: EN, SQ, ...
                          duration_seconds  INTEGER      NOT NULL,
                          aspect_ratio      VARCHAR(10)  NOT NULL,              -- PORTRAIT, LANDSCAPE, SQUARE
                          voice_id          VARCHAR(100),                       -- NULL = default voice of the language
                          title             VARCHAR(200),                       -- from the script
                          script            JSONB,                              -- the current script, editable during review
                          status            VARCHAR(20)  NOT NULL,
                          error_code        VARCHAR(50),
                          error_message     VARCHAR(500),
                          created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

                          CONSTRAINT ck_projects_status CHECK (status IN
                                                               ('SCRIPT_PENDING', 'SCRIPT_REVIEW', 'ASSETS_PENDING', 'ASSETS_READY',
                                                                'RENDERING', 'DONE', 'FAILED', 'CANCELLED')),
                          CONSTRAINT ck_projects_duration CHECK (duration_seconds BETWEEN 15 AND 90)
);

CREATE INDEX idx_projects_user_created ON projects (user_id, created_at DESC);

CREATE TRIGGER trg_projects_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


CREATE TABLE jobs (
                      id             UUID PRIMARY KEY,
                      project_id     UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
                      kind           VARCHAR(10)  NOT NULL,                 -- SCRIPT, ASSETS, RENDER
                      status         VARCHAR(10)  NOT NULL,
                      error_code     VARCHAR(50),
                      error_message  VARCHAR(500),
                      started_at     TIMESTAMPTZ,
                      finished_at    TIMESTAMPTZ,
                      created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                      updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

                      CONSTRAINT ck_jobs_kind   CHECK (kind IN ('SCRIPT', 'ASSETS', 'RENDER')),
                      CONSTRAINT ck_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_jobs_project ON jobs (project_id, created_at);
-- Workers look for jobs that still have work to do.
CREATE INDEX idx_jobs_active ON jobs (status) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


CREATE TABLE job_steps (
                           id             UUID PRIMARY KEY,
                           job_id         UUID           NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
                           type           VARCHAR(10)    NOT NULL,               -- SCRIPT, IMAGE, VOICE, RENDER
                           scene_index    INTEGER        NOT NULL DEFAULT -1,    -- 0, 1, 2... ; -1 for steps of the whole video
                           status         VARCHAR(10)    NOT NULL,
                           attempts       INTEGER        NOT NULL DEFAULT 0,
                           output_key     VARCHAR(500),                          -- storage key of the file this step produced
                           output_meta    JSONB,                                 -- e.g. {"width":1024,"height":1820} or word timings
                           cost_usd       NUMERIC(12, 6) NOT NULL DEFAULT 0,
                           error_code     VARCHAR(50),
                           error_message  VARCHAR(500),
                           started_at     TIMESTAMPTZ,
                           finished_at    TIMESTAMPTZ,
                           created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
                           updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

                           CONSTRAINT ck_job_steps_type   CHECK (type IN ('SCRIPT', 'IMAGE', 'VOICE', 'RENDER')),
                           CONSTRAINT ck_job_steps_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    -- Idempotency: one image and one voice per scene per job, never two.
                           CONSTRAINT uq_job_steps UNIQUE (job_id, type, scene_index)
);

CREATE INDEX idx_job_steps_job ON job_steps (job_id, type, scene_index);

CREATE TRIGGER trg_job_steps_updated_at
    BEFORE UPDATE ON job_steps
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- provider_usage.job_id (V6) now has something to point at. No FK on purpose: dev smoke runs
-- (3.5) use job ids that are not in this table, and cost rows must survive a deleted project.