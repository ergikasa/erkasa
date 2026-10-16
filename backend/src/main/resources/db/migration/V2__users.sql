-- V2: users table (phase 2, auth).

CREATE TABLE users (
                       id              UUID PRIMARY KEY,
    -- Always stored lowercase and trimmed by the app, so the unique index is enough.
                       email           VARCHAR(320) NOT NULL,
    -- NULL for users who only log in with Google (sub-task 2.5).
                       password_hash   VARCHAR(100),
                       display_name    VARCHAR(100),
                       email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
                       role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
                       created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

                       CONSTRAINT uq_users_email UNIQUE (email),
                       CONSTRAINT ck_users_role  CHECK (role IN ('USER', 'ADMIN'))
);

-- Keeps updated_at current even for changes made outside the app (function from V1).
CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();