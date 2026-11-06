-- Free-form notes attached to a vehicle (service reminders, known faults,
-- accessories to check, etc.).
--
-- Existing vehicle rows retain their data; the new column is nullable, so no
-- backfill is required and no value is inferred for vehicles that have none.
--
-- IMPORTANT: this project has no Flyway or Liquibase dependency on the classpath
-- (see pom.xml). Files in this folder follow the Flyway naming convention but are
-- NOT executed automatically. Apply the statement below manually, or rely on
-- spring.jpa.hibernate.ddl-auto=update in the local/dev profile.
--
-- Production runs with ddl-auto=validate (application-prod.yml), so the column
-- must exist before the application starts there or startup will fail.
ALTER TABLE vehicles
    ADD COLUMN notes TEXT NULL;
