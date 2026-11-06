-- V13: Appointment type for bookings (FR appointment desk).
--
-- The New Appointment form has always offered Walk-in / Online / Pickup-Drop,
-- but the Appointment entity had no column for it, so the choice was discarded
-- on save and every row came back empty. This column gives it somewhere to live.
--
-- EXISTING DATA IS PRESERVED. The column is nullable and no value is backfilled:
-- appointments booked before this migration keep NULL appointment_type, which
-- the API returns as null and the UI renders as "—". No row is updated and no
-- row is deleted by this script.
--
-- VARCHAR rather than a native ENUM: MySQL ENUM makes widening the allowed set
-- a schema alteration, and the service already rejects anything outside the
-- supported list (WALK_IN / ONLINE / PICKUP_DROP) on the way in.
--
-- IMPORTANT: this project has no Flyway or Liquibase dependency on the classpath
-- (see pom.xml). Files in this folder follow the Flyway naming convention but are
-- NOT executed automatically. Apply the statement below manually, or rely on
-- spring.jpa.hibernate.ddl-auto=update in the local/dev profile.
--
-- Production runs with ddl-auto=validate (application-prod.yml), so the column
-- must exist before the application starts there or startup will fail.

ALTER TABLE appointments
    ADD COLUMN appointment_type VARCHAR(40) NULL;