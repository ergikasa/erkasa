-- V14: videos up to 100 seconds (V2 task A5), was 90.
-- Only the upper bound changes; existing rows (15-90 s) all still pass the new check.

ALTER TABLE projects DROP CONSTRAINT ck_projects_duration;
ALTER TABLE projects ADD CONSTRAINT ck_projects_duration CHECK (duration_seconds BETWEEN 15 AND 100);
