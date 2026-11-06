-- V9: Record the GST rate actually applied to each billing document.
--
-- Until now GST came from a single workshop-wide setting
-- (app.billing.tax-rate-percent, default 18), so an invoice could not be
-- billed at, say, 5% for exempt goods while the rest of the garage stayed on
-- 18%. This adds invoices.gst_rate and estimates.gst_rate.
--
-- Both columns are NULLABLE and deliberately so:
--   * NULL means "this document was priced at the configured default". That is
--     true of every row written before this migration, so no backfill is needed
--     and none should be attempted — inventing a rate for historical documents
--     would assert something the ledger does not actually record.
--   * A non-NULL value is the percentage actually applied to that document.
--
-- The stored rate is what the document was billed at, not a copy of current
-- configuration: changing app.billing.tax-rate-percent later must not restate
-- documents already issued. BillingCalculator.resolveRate() falls back to the
-- configured default when the column is NULL, which is why the fallback lives
-- in the calculator rather than being backfilled here.
--
-- NOTE ON EXECUTION
--   Flyway is NOT on the classpath in this project (see pom.xml), so this file
--   is inert documentation of the intended schema, exactly like V1-V8.
--     * Development  (ddl-auto: update)  -> Hibernate applies this automatically
--                                          on startup.
--     * Production   (ddl-auto: validate)-> Hibernate does NOT apply it and will
--                                          fail startup on the missing columns,
--                                          so this MUST be run manually before
--                                          deploying this change.

ALTER TABLE invoices
    ADD COLUMN gst_rate DECIMAL(5,2) NULL COMMENT 'GST percentage applied to this invoice; NULL = configured default';

ALTER TABLE estimates
    ADD COLUMN gst_rate DECIMAL(5,2) NULL COMMENT 'GST percentage applied to this estimate; NULL = configured default';