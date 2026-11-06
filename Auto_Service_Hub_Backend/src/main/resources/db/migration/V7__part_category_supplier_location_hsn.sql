-- V7: Persist category, supplier, storage location and HSN code on parts (FR-INV-1).
--
-- The Add Spare Part form has always collected these four fields, but the Part
-- entity had no columns for them, so they could not be saved.
--
-- All four are NULLABLE and added as new columns, so every existing part row
-- keeps all of its current data and simply reads back as uncategorised /
-- unassigned. No backfill is required or possible: a part created before this
-- migration genuinely has no category on file.
--
-- NOTE ON EXECUTION
--   Flyway is NOT on the classpath in this project (see pom.xml), so this file
--   is inert documentation of the intended schema, exactly like V1-V6.
--     * Development  (ddl-auto: update)  -> Hibernate applies this automatically
--                                          on startup; running this by hand is
--                                          optional but harmless (IF NOT EXISTS).
--     * Production   (ddl-auto: validate)-> Hibernate does NOT apply it and will
--                                          fail startup on the missing columns,
--                                          so this MUST be run manually before
--                                          deploying the new backend.

ALTER TABLE parts
    ADD COLUMN category          VARCHAR(30)     NULL,
    ADD COLUMN storage_location  VARCHAR(255)    NULL,
    ADD COLUMN hsn_code          VARCHAR(30)     NULL,
    ADD COLUMN supplier_id       BIGINT          NULL,
    ADD CONSTRAINT fk_parts_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
        ON DELETE RESTRICT;

CREATE INDEX idx_parts_supplier_id ON parts(supplier_id);
CREATE INDEX idx_parts_category     ON parts(category);

-- 'ON DELETE RESTRICT' is deliberate, and is why SupplierServiceImpl.delete now
-- checks for referencing parts and returns 409. Letting the FK silently drop the
-- reference would quietly unassign every part bought from that supplier.