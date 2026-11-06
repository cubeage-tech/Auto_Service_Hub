-- V5: Add role, joining_date and salary columns to the mechanics table.
--
-- All three columns are nullable so that existing mechanic rows are
-- unaffected and no backfill is required.  Salary uses DECIMAL(12,2)
-- per the SRS monetary-value guideline (never floating-point).
-- Role is a short VARCHAR; no FK to a look-up table because the valid
-- values are enforced by the application layer (SRS 4.6).
ALTER TABLE mechanics
    ADD COLUMN role         VARCHAR(60)     NULL,
    ADD COLUMN joining_date DATE            NULL,
    ADD COLUMN salary       DECIMAL(12, 2)  NULL;
