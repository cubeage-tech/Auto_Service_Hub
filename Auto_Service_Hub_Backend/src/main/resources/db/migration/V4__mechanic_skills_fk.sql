-- V4: Wire mechanic_skills to mechanics via mechanic_id FK (SRS 4.6).
--
-- The mechanic_skills table was created by ddl-auto but the entity had no
-- relationship to Mechanic, so every skill row was orphaned.
-- This migration adds the FK column and index.  Existing rows (none in
-- production — skills were never saveable) are unaffected; NULL is allowed
-- so that any stale rows do not violate the constraint.
ALTER TABLE mechanic_skills
    ADD COLUMN mechanic_id BIGINT NULL,
    ADD CONSTRAINT fk_mechanic_skills_mechanic
        FOREIGN KEY (mechanic_id) REFERENCES mechanics(id)
        ON DELETE CASCADE;

CREATE INDEX idx_mechanic_skills_mechanic_id ON mechanic_skills(mechanic_id);
