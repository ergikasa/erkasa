-- V4: "Sign in with Google" (phase 2, auth).
-- google_sub is Google's permanent id for a person. Emails can change; "sub" never does,
-- so it is what we match on after the first Google login.

ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);

-- Several NULLs are allowed (users without Google); two users can never share one Google account.
ALTER TABLE users ADD CONSTRAINT uq_users_google_sub UNIQUE (google_sub);