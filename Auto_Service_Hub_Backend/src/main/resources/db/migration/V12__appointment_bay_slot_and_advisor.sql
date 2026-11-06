-- V12: Bay / slot and assigned advisor for appointments (FR appointment desk).
--
-- The appointment form has always collected a bay and an advisor, but the
-- Appointment entity had nowhere to put them, so both were discarded on save
-- and every row came back empty. These two columns give them somewhere to live.
--
-- EXISTING DATA IS PRESERVED. Both columns are nullable and no value is
-- inferred: appointments booked before this migration keep NULL bay and NULL
-- advisor, which the form renders as "Not assigned". No row is updated and no
-- row is deleted by this script.
--
-- The advisor is a foreign key onto `users`, because advisors are service-desk
-- staff in this schema, not a separate table. The service layer additionally
-- requires that referenced user to be active and to hold the SERVICE_ADVISOR
-- role; this constraint only guarantees the user exists.
--
-- IMPORTANT: this project has no Flyway or Liquibase dependency on the classpath
-- (see pom.xml). Files in this folder follow the Flyway naming convention but are
-- NOT executed automatically. Apply the statements below manually, or rely on
-- spring.jpa.hibernate.ddl-auto=update in the local/dev profile.
--
-- Production runs with ddl-auto=validate (application-prod.yml), so these
-- columns must exist before the application starts there or startup will fail.

ALTER TABLE appointments
    ADD COLUMN bay_slot VARCHAR(40) NULL,
    ADD COLUMN advisor_id BIGINT NULL;

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_advisor
        FOREIGN KEY (advisor_id) REFERENCES users(id)
        ON DELETE SET NULL;

CREATE INDEX idx_appointments_advisor_id ON appointments(advisor_id);