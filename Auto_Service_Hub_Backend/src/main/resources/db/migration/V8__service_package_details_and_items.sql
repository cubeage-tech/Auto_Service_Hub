-- V8: Persist the rest of the service package form (FR-PKG-1).
--
-- The Create Package form collects duration, GST rate, discount, card colour,
-- a popular badge and the included services/parts. The service_packages table
-- had columns for none of them, and package_items existed but had no link back
-- to a package, so none of it could be saved.
--
-- Every column added here is NULLABLE, so existing package rows keep all of
-- their data and simply read back with no duration, no discount and no items.
-- No backfill is possible or needed: a package saved before this migration
-- genuinely has no duration on file.
--
-- NOTE ON EXECUTION
--   Flyway is NOT on the classpath in this project (see pom.xml), so this file
--   is inert documentation of the intended schema, exactly like V1-V7.
--     * Development  (ddl-auto: update)  -> Hibernate applies this automatically
--                                          on startup.
--     * Production   (ddl-auto: validate)-> Hibernate does NOT apply it and will
--                                          fail startup on the missing columns,
--                                          so this MUST be run manually before
--                                          deploying the new backend.

ALTER TABLE service_packages
    ADD COLUMN duration             VARCHAR(255) NULL,
    ADD COLUMN gst_rate             DECIMAL(5,2) NULL,
    ADD COLUMN discount_percentage  DECIMAL(5,2) NULL,
    ADD COLUMN color                VARCHAR(20)  NULL,
    ADD COLUMN popular              BIT(1)       NULL;

-- package_items already existed (item_name, item_type) but had no owner, so no
-- row could ever belong to a package. service_package_id links each item to one.
ALTER TABLE package_items
    ADD COLUMN service_package_id BIGINT NULL,
    ADD CONSTRAINT fk_package_items_service_package
        FOREIGN KEY (service_package_id) REFERENCES service_packages(id)
        ON DELETE CASCADE;

CREATE INDEX idx_package_item_service_package_id
    ON package_items(service_package_id);

-- ON DELETE CASCADE is deliberate: a hard-deleted package must not leave
-- orphan item rows behind, which have no meaning on their own.