package com.autoservicehub.entity;

/**
 * The business actions worth an audit record (SRS 6 - Auditability).
 *
 * <p>An enum rather than a free string, because the value of an audit trail
 * comes from being able to ask "show me every DELETE on a PAYMENT" and get a
 * complete, typo-free answer. A free string cannot promise that: one misspelling
 * splits one history into two.
 *
 * <p>Deliberately <b>not</b> exhaustive. It lists actions that change money,
 * stock, work state or access — not every read, and not every field tweak. A
 * trail that logs everything is a trail nobody reads, and reading is the whole
 * point of having one.
 */
public enum AuditAction {

    // ── Job cards ────────────────────────────────────────────────────────
    JOB_CARD_CREATE,
    JOB_CARD_UPDATE,
    JOB_CARD_STATUS_CHANGE,
    JOB_CARD_DELETE,

    // ── Repair tasks / labour (FR-JOB-3, FR-JOB-5) ───────────────────────
    JOB_TASK_CREATE,
    JOB_TASK_UPDATE,
    JOB_TASK_STATUS_CHANGE,
    JOB_TASK_ASSIGN_MECHANIC,
    JOB_TASK_UPDATE_WORK_NOTES,
    JOB_TASK_DELETE,

    // ── Inventory (FR-INV-2) ─────────────────────────────────────────────
    STOCK_IN,
    STOCK_OUT,
    STOCK_ADJUSTMENT,

    // ── Purchasing (FR-INV-2) ────────────────────────────────────────────
    SUPPLIER_CREATE,
    SUPPLIER_UPDATE,
    SUPPLIER_DELETE,
    PURCHASE_CREATE,
    PURCHASE_RECEIVE,

    // ── Estimates and invoices (FR-BILL-1..3) ────────────────────────────
    ESTIMATE_CREATE,
    ESTIMATE_UPDATE,
    ESTIMATE_DELETE,
    ESTIMATE_CONVERT,
    INVOICE_CREATE,
    INVOICE_UPDATE,
    INVOICE_DELETE,
    INVOICE_LABOUR_ADDED,

    // ── Payments (FR-BILL-3) ─────────────────────────────────────────────
    PAYMENT_CREATE,
    PAYMENT_UPDATE,
    PAYMENT_DELETE,
    PAYMENT_STATUS_CHANGE,

    // ── Customers and vehicles ───────────────────────────────────────────
    CUSTOMER_CREATE,
    CUSTOMER_UPDATE,
    CUSTOMER_DELETE,
    VEHICLE_CREATE,
    VEHICLE_UPDATE,
    VEHICLE_DELETE
}