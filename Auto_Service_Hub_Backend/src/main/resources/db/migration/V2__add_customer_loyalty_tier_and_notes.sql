-- Apply once before starting the production application with ddl-auto=validate.
-- Existing customer rows retain their data; the new fields are nullable.
ALTER TABLE customers
    ADD COLUMN loyalty_tier VARCHAR(20) NULL,
    ADD COLUMN notes TEXT NULL;
