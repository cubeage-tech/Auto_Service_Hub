-- =============================================================================
-- Phase 3 additive schema: quality checks (SRS 4.5 FR-JOB-6, SRS 8.2, SRS 12 BR-02)
-- =============================================================================
-- This project has no Flyway/Liquibase runner; apply this script explicitly
-- before deploying with application-prod.yml (ddl-auto=validate), exactly as
-- mechanic_module_additive.sql already requires.
--
-- SAFETY FOR EXISTING RECORDS
--   Fully additive. It creates one new table and no ALTER of any existing table,
--   so no existing job card, task, invoice or customer row is touched or
--   invalidated. Rows are only added from the moment the application starts
--   writing quality checks. Safe to run against a populated database.
--
-- MANUAL APPLICATION REQUIRED: this file is NOT executed by the build or by the
-- application. Run it once per environment, for example:
--     mysql -u <user> -p <database> < phase3_additive.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS quality_checks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_card_id     BIGINT       NOT NULL,
    result          VARCHAR(10)  NOT NULL,
    remarks         TEXT         NULL,
    checked_by_user_id BIGINT    NOT NULL,
    checked_at      DATETIME(6)  NOT NULL,
    attempt_no      INT          NOT NULL,
    version         BIGINT       NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),

    -- Serves BOTH read paths, so no additional index is required:
    --   * QC history / latest attempt: WHERE job_card_id = ? ORDER BY attempt_no DESC
    --   * next attempt number:         WHERE job_card_id = ? (MAX(attempt_no))
    -- Its leftmost prefix is job_card_id, so it is a covering index for both, and it
    -- also acts as the last-resort integrity backstop against two writers computing
    -- the same attempt number despite the pessimistic job-card row lock.
    CONSTRAINT uq_quality_checks_job_card_attempt UNIQUE (job_card_id, attempt_no),

    -- result is a closed vocabulary in the entity. NOTE: MySQL only ENFORCES CHECK
    -- constraints from 8.0.16 onwards; on 8.0.15 and earlier (and on 5.7) this clause
    -- parses and is silently ignored, leaving QualityCheckServiceImpl as the only
    -- enforcement layer. Minimum supported server for enforcement: MySQL 8.0.16.
    CONSTRAINT ck_quality_checks_result CHECK (result IN ('PASS', 'FAIL')),

    CONSTRAINT fk_quality_checks_job_card
        FOREIGN KEY (job_card_id)     REFERENCES job_cards (id),
    CONSTRAINT fk_quality_checks_checked_by
        FOREIGN KEY (checked_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB;

-- No standalone CREATE INDEX statements are issued on purpose:
-- uq_quality_checks_job_card_attempt already covers every read path (see above), and
-- MySQL has no "CREATE INDEX IF NOT EXISTS", so bare CREATE INDEX would make this
-- script fail on a second run. CREATE TABLE IF NOT EXISTS is a no-op when the table
-- already exists, so re-running this file is safe.
--
-- RERUN SAFETY
--   Re-running this file is safe: the only executable statement is the idempotent
--   CREATE TABLE. Nothing else needs to be re-executed.
