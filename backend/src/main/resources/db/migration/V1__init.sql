-- V1: database foundation for erkasa.
-- Flyway runs this once and records it in the flyway_schema_history table.
-- Never edit this file after it has run; add V2__..., V3__... instead.

-- Case-insensitive text, used for emails (Ergi@x.com and ergi@x.com are the same user).
CREATE EXTENSION IF NOT EXISTS citext;

-- Shared trigger function: keeps the updated_at column current on every UPDATE.
-- Tables in later phases attach it like this:
--   CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
--   FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;