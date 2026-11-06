-- V14: Record service packages applied to a customer (FR-PKG-2).
--
-- A service package could be defined but nothing recorded that anyone had
-- actually taken one: there was no link from a package to the customer or
-- vehicle it was sold to, so "Apply Package" had nothing to write and the
-- packages page could not show whether a package had been used. This table is
-- that link.
--
-- NEW TABLE. No existing table is altered, no existing row is updated and no
-- row is deleted by this script. Applying packages simply starts recording
-- from the moment the backend is deployed.
--
-- IMPORTANT: this project has no Flyway or Liquibase dependency on the classpath
-- (see pom.xml). Files in this folder follow the Flyway naming convention but are
-- NOT executed automatically. Apply the statements below manually, or rely on
-- spring.jpa.hibernate.ddl-auto=update in the local/dev profile.
--
-- Production runs with ddl-auto=validate (application-prod.yml), so this table
-- must exist before the application starts there or startup will fail.

CREATE TABLE package_applications (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NULL,

    -- Optimistic locking version, matching BaseEntity.
    version                     BIGINT       NULL,

    -- Ownership links. All three are NOT NULL: an application that could not be
    -- attributed to a package, a customer and a vehicle could never be reported on.
    service_package_id          BIGINT       NOT NULL,
    customer_id                 BIGINT       NOT NULL,
    vehicle_id                  BIGINT       NOT NULL,

    -- ── Snapshot of the package terms at the moment of application ──
    -- Copied, not read through the relationship. A package's price and terms are
    -- editable at any time, so reading them back later would silently restate
    -- history: raising a price tomorrow would change what this customer was
    -- already quoted. These columns keep the record of what was agreed.
    package_name                VARCHAR(255) NULL,
    package_type                VARCHAR(255) NULL,
    package_price               DECIMAL(19,2) NULL,
    package_gst_rate            DECIMAL(5,2)  NULL,
    package_discount_percentage DECIMAL(5,2)  NULL,
    package_duration            VARCHAR(255) NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_package_applications_service_package
        FOREIGN KEY (service_package_id) REFERENCES service_packages(id),
    CONSTRAINT fk_package_applications_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_package_applications_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),

    -- One package may be applied to a given vehicle only once. Enforced here as
    -- well as in the service layer, because a service-layer check alone races:
    -- two advisors pressing Apply at the same moment would both pass it.
    -- Scoped per (package, vehicle), not per customer, so a customer with two
    -- cars can hold the same package on both.
    CONSTRAINT uq_package_application_package_vehicle
        UNIQUE (service_package_id, vehicle_id)
);

-- Deliberately NO ON DELETE CASCADE on the three foreign keys. An application is a
-- record of what a customer agreed to and is the basis for revenue reporting, so it
-- must not disappear because a catalogue entry was tidied up. Deleting a package
-- that has been applied is refused by the database, which GlobalExceptionHandler
-- reports as a 409 DATA_CONFLICT.

CREATE INDEX idx_package_application_service_package_id
    ON package_applications(service_package_id);

CREATE INDEX idx_package_application_customer_id
    ON package_applications(customer_id);

CREATE INDEX idx_package_application_vehicle_id
    ON package_applications(vehicle_id);

-- The list endpoints page newest-first by (created_at, id), so the ordering
-- columns are indexed too.
CREATE INDEX idx_package_application_created_at_id
    ON package_applications(created_at, id);
