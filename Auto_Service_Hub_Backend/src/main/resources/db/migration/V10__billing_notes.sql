-- V10: Persist the free-text notes on billing documents.
--
-- The Notes box on the New Estimate / Invoice form has been present in the UI
-- since the billing modal was built, but it was inert: neither invoices nor
-- estimates had a column to hold it, so the text was typed, discarded on save
-- and could never be read back. This adds the storage behind it.
--
-- Both columns are NULLABLE and deliberately so:
--   * NULL means "nothing was written on this document". That is true of every
--     row written before this migration, so no backfill is needed and none
--     should be attempted — there is no recorded note to invent one from.
--   * InvoiceServiceImpl/EstimateServiceImpl normalise blank input to NULL, so
--     "cleared the notes" and "never filled them in" are the same stored state
--     and both come back as JSON null.
--
-- The columns are TEXT rather than a bounded VARCHAR: notes are never computed
-- on, never indexed and never sorted, so there is nothing to gain from a length
 * limit at the column level. The 2000-character ceiling is enforced at the edge
-- instead, by @Size on InvoiceRequestDTO/EstimateRequestDTO, so an oversized
-- note is rejected with a clear message rather than being silently truncated.
--
-- Existing rows are untouched; the statements are additive only.
--
-- NOTE ON EXECUTION
--   Flyway is NOT on the classpath in this project (see pom.xml), so this file
--   is inert documentation of the intended schema, exactly like V1-V9.
--     * Development  (ddl-auto: update)  -> Hibernate applies this automatically
--                                          on startup.
--     * Production   (ddl-auto: validate)-> Hibernate does NOT apply it and will
--                                          fail startup on the missing columns,
--                                          so this MUST be run manually before
--                                          deploying this change.

ALTER TABLE invoices
    ADD COLUMN notes TEXT NULL COMMENT 'Free-text remarks on the invoice; NULL = none';

ALTER TABLE estimates
    ADD COLUMN notes TEXT NULL COMMENT 'Free-text remarks on the estimate; NULL = none';