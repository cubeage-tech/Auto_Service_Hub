SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- ROLES (SRS v1.0 Roles & Permissions)
-- ============================================================
INSERT INTO roles (created_at, updated_at, name) VALUES
(NOW(6), NOW(6), 'GARAGE_OWNER'),
(NOW(6), NOW(6), 'MANAGER'),
(NOW(6), NOW(6), 'SERVICE_ADVISOR'),
(NOW(6), NOW(6), 'MECHANIC'),
(NOW(6), NOW(6), 'INVENTORY_MANAGER'),
(NOW(6), NOW(6), 'BILLING_USER'),
(NOW(6), NOW(6), 'CUSTOMER');

-- ============================================================
-- USERS  (password = "password123" BCrypt)
-- There is no ADMIN account: the ADMIN role was removed, so every account
-- below carries one of the roles seeded above. Each user's role_id is
-- resolved by matching the role NAME of the account against a `roles` row,
-- instead of a hard-coded numeric id, so the assignments stay correct even
-- when the role rows receive different auto-generated ids.
-- The `roles` insert above must therefore run first (it does, earlier in
-- this file); a name listed below with no matching role row is skipped
-- rather than being mapped onto some other role.
-- ============================================================
INSERT INTO users (created_at, updated_at, username, password_hash, active, full_name, role_id)
SELECT NOW(6), NOW(6), u.username, u.password_hash, u.active, u.full_name, r.id
FROM (
              SELECT 'owner'      AS username,
                     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y' AS password_hash,
                     1 AS active,
                     'Garage Owner' AS full_name,
                     'GARAGE_OWNER' AS role_name
    UNION ALL SELECT 'manager',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Operations Manager',   'MANAGER'
    UNION ALL SELECT 'advisor',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Service Advisor',      'SERVICE_ADVISOR'
    UNION ALL SELECT 'mechanic1',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Ravi Kumar',           'MECHANIC'
    UNION ALL SELECT 'inventory1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Inventory Manager',    'INVENTORY_MANAGER'
    UNION ALL SELECT 'billing1',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Billing User',         'BILLING_USER'
    UNION ALL SELECT 'customer1',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lh7y', 1, 'Customer Portal User', 'CUSTOMER'
) AS u
JOIN roles r ON r.name = u.role_name;

-- ============================================================
-- CUSTOMERS
-- ============================================================
INSERT INTO customers (created_at, updated_at, name, phone, email, address, status) VALUES
(NOW(6), NOW(6), 'Arjun Mehta',  '9876543210', 'arjun@example.com',  'Pune, MH',      'ACTIVE'),
(NOW(6), NOW(6), 'Priya Sharma', '9876543120', 'priya@example.com',  'Delhi, DL',     'ACTIVE'),
(NOW(6), NOW(6), 'Rohit Desai',  '9876543130', 'rohit@example.com',  'Ahmedabad, GJ', 'ACTIVE'),
(NOW(6), NOW(6), 'Neha Joshi',   '9876543140', 'neha@example.com',   'Pune, MH',      'ACTIVE'),
(NOW(6), NOW(6), 'Vikram Singh', '9876543150', 'vikram@example.com', 'Lucknow, UP',   'ACTIVE'),
(NOW(6), NOW(6), 'Kavita Rao',   '9876543160', 'kavita@example.com', 'Bengaluru, KA', 'ACTIVE');

-- ============================================================
-- VEHICLES
-- ============================================================
INSERT INTO vehicles (created_at, updated_at, registration_no, make, model, variant, year, mileage, customer_id) VALUES
(NOW(6), NOW(6), 'MH-12-AB-4521', 'Maruti',  'Swift',     'VXI',     2020, 43200, 1),
(NOW(6), NOW(6), 'DL-01-CZ-9834', 'Hyundai', 'Creta',     'SX',      2021, 28500, 2),
(NOW(6), NOW(6), 'GJ-05-XY-7712', 'Toyota',  'Innova',    'GX',      2019, 72000, 3),
(NOW(6), NOW(6), 'MH-14-PQ-3356', 'Honda',   'City',      'ZX',      2022, 15000, 4),
(NOW(6), NOW(6), 'UP-32-GH-1190', 'Toyota',  'Fortuner',  'Legender',2020, 55000, 5),
(NOW(6), NOW(6), 'KA-03-MN-5567', 'Maruti',  'Baleno',    'Alpha',   2021, 32000, 6);

-- ============================================================
-- MECHANICS
-- ============================================================
INSERT INTO mechanics (created_at, updated_at, name, employee_code, phone, experience_years, status) VALUES
(NOW(6), NOW(6), 'Ravi Kumar',   'EMP-001', '9811111111', 8, 'ACTIVE'),
(NOW(6), NOW(6), 'Amit Patel',   'EMP-002', '9822222222', 6, 'ACTIVE'),
(NOW(6), NOW(6), 'Suresh Nair',  'EMP-003', '9833333333', 5, 'ACTIVE'),
(NOW(6), NOW(6), 'Deepak Verma', 'EMP-004', '9844444444', 4, 'ACTIVE'),
(NOW(6), NOW(6), 'Kiran Joshi',  'EMP-005', '9855555555', 3, 'ACTIVE');

-- ============================================================
-- PARTS
-- ============================================================
INSERT INTO parts (created_at, updated_at, sku, name, unit, selling_price, purchase_price, stock_qty, min_stock) VALUES
(NOW(6), NOW(6), 'ENO-7742', 'Engine Oil 5W-30 (1L)',   'Litre',  320.00,  240.00, 48, 20),
(NOW(6), NOW(6), 'BRP-2211', 'Brake Pads Front (Pair)', 'Set',   1200.00,  900.00, 12,  8),
(NOW(6), NOW(6), 'ACF-5503', 'AC Filter',               'Piece',  450.00,  320.00,  3,  5),
(NOW(6), NOW(6), 'ATF-9902', 'ATF Oil (1L)',             'Litre',  580.00,  420.00, 18, 10),
(NOW(6), NOW(6), 'SPK-1144', 'Spark Plugs (Set of 4)',  'Set',    880.00,  640.00, 22,  8),
(NOW(6), NOW(6), 'TYR-3389', 'Tyre 195/65 R15',         'Piece', 4800.00, 3800.00,  4,  4),
(NOW(6), NOW(6), 'BAT-6670', 'Battery 60Ah',            'Piece', 6200.00, 4800.00,  7,  3),
(NOW(6), NOW(6), 'OIF-3301', 'Oil Filter',              'Piece',  180.00,  120.00,  2, 10),
(NOW(6), NOW(6), 'AFT-1102', 'Air Filter',              'Piece',  350.00,  250.00, 15,  8),
(NOW(6), NOW(6), 'CLT-4421', 'Clutch Plate',            'Set',   3200.00, 2400.00,  6,  3);

-- ============================================================
-- JOB CARDS
-- ============================================================
INSERT INTO job_cards (created_at, updated_at, job_card_number, customer_id, vehicle_id, mechanic_id, service_type, complaint, status, odometer_reading, estimated_cost, estimated_delivery, assigned_date) VALUES
(NOW(6), NOW(6), 'JC-20260101120000', 1, 1, 1, 'Engine Overhaul',    'Engine noise reported',     'IN_REPAIR',     43200, 18500.00, DATE_ADD(NOW(), INTERVAL 1 DAY), NOW(6)),
(NOW(6), NOW(6), 'JC-20260101120001', 2, 2, 2, 'Full Service',        'Periodic full service',     'QUALITY_CHECK', 28500,  8200.00, NOW(6),                          NOW(6)),
(NOW(6), NOW(6), 'JC-20260101120002', 3, 3, 3, 'AC Repair + Service', 'AC not cooling',            'DELIVERED',     72000, 12400.00, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(6)),
(NOW(6), NOW(6), 'JC-20260101120003', 4, 4, 1, 'Brake Replacement',   'Brake noise and vibration', 'INSPECTION',    15000,  4800.00, DATE_ADD(NOW(), INTERVAL 1 DAY), NOW(6)),
(NOW(6), NOW(6), 'JC-20260101120004', 5, 5, 2, 'Suspension + Tyres',  'Rough ride, tyre wear',     'RECEIVED',      55000, 32000.00, DATE_ADD(NOW(), INTERVAL 2 DAY), NOW(6)),
(NOW(6), NOW(6), 'JC-20260101120005', 6, 6, 4, 'Basic Service',       'Routine service due',       'DELIVERED',     32000,  3200.00, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(6));

-- ============================================================
-- APPOINTMENTS
-- ============================================================
INSERT INTO appointments (created_at, updated_at, customer_id, vehicle_id, appointment_at, service_type, status, notes) VALUES
(NOW(6), NOW(6), 1, 1, DATE_ADD(NOW(), INTERVAL 1 DAY), 'Full Service',      'SCHEDULED', 'Morning slot preferred'),
(NOW(6), NOW(6), 2, 2, DATE_ADD(NOW(), INTERVAL 2 DAY), 'AC Repair',         'SCHEDULED', NULL),
(NOW(6), NOW(6), 3, 3, DATE_ADD(NOW(), INTERVAL 3 DAY), 'Tyre Replacement',  'SCHEDULED', NULL),
(NOW(6), NOW(6), 4, 4, DATE_ADD(NOW(), INTERVAL 5 DAY), 'Brake Check',       'SCHEDULED', NULL),
(NOW(6), NOW(6), 5, 5, DATE_ADD(NOW(), INTERVAL 7 DAY), 'Engine Inspection', 'SCHEDULED', NULL);

SET FOREIGN_KEY_CHECKS = 1;
