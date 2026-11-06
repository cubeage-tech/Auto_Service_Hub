-- Existing vehicles retain their data. Fuel type and status remain NULL until
-- explicitly known; no values are inferred from vehicle make, model, or job cards.
ALTER TABLE vehicles
    ADD COLUMN fuel_type VARCHAR(20) NULL,
    ADD COLUMN status VARCHAR(32) NULL;
