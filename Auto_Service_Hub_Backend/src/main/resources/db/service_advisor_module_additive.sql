-- Manual additive schema changes for Service Advisor module support.
-- Do not execute automatically: this project has no configured migration runner.
-- Apply once only after verifying the target schema and taking a database backup.
-- Every new relationship column is nullable to retain existing rows.

ALTER TABLE customers
    ADD COLUMN city VARCHAR(255) NULL,
    ADD COLUMN pincode VARCHAR(255) NULL,
    ADD COLUMN loyalty_tier VARCHAR(255) NULL,
    ADD COLUMN notes TEXT NULL,
    ADD COLUMN preferences TEXT NULL;

ALTER TABLE vehicles
    ADD COLUMN fuel_type VARCHAR(255) NULL,
    ADD COLUMN notes TEXT NULL;

ALTER TABLE appointments
    ADD COLUMN appointment_type VARCHAR(255) NULL,
    ADD COLUMN time_slot VARCHAR(255) NULL,
    ADD COLUMN bay VARCHAR(255) NULL,
    ADD COLUMN assigned_advisor_id BIGINT NULL,
    ADD KEY idx_appointments_bay_at (bay, appointment_at),
    ADD CONSTRAINT fk_appointments_assigned_advisor FOREIGN KEY (assigned_advisor_id) REFERENCES users (id);

ALTER TABLE inspections
    ADD COLUMN vehicle_id BIGINT NULL,
    ADD COLUMN job_card_id BIGINT NULL,
    ADD CONSTRAINT fk_inspections_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    ADD CONSTRAINT fk_inspections_job_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id);

ALTER TABLE inspection_items
    ADD COLUMN inspection_id BIGINT NULL,
    ADD CONSTRAINT fk_inspection_items_inspection FOREIGN KEY (inspection_id) REFERENCES inspections (id);

ALTER TABLE service_packages
    ADD COLUMN code VARCHAR(255) NULL,
    ADD COLUMN duration_minutes INT NULL,
    ADD COLUMN validity_days INT NULL,
    ADD COLUMN gst_rate DECIMAL(19, 2) NULL,
    ADD COLUMN discount DECIMAL(19, 2) NULL;

ALTER TABLE package_items
    ADD COLUMN service_package_id BIGINT NULL,
    ADD COLUMN part_id BIGINT NULL,
    ADD CONSTRAINT fk_package_items_service_package FOREIGN KEY (service_package_id) REFERENCES service_packages (id),
    ADD CONSTRAINT fk_package_items_part FOREIGN KEY (part_id) REFERENCES parts (id);

ALTER TABLE estimates
    ADD COLUMN job_card_id BIGINT NULL,
    ADD CONSTRAINT fk_estimates_job_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id);

ALTER TABLE estimate_items
    ADD COLUMN estimate_id BIGINT NULL,
    ADD CONSTRAINT fk_estimate_items_estimate FOREIGN KEY (estimate_id) REFERENCES estimates (id);

ALTER TABLE payments
    ADD COLUMN invoice_id BIGINT NULL,
    ADD CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id);

ALTER TABLE notifications
    ADD COLUMN recipient_user_id BIGINT NULL,
    ADD CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id);
