package com.autoservicehub.util;

/**
 * Shared constants (status values, roles) referenced across services/controllers.
 * Keep in sync with SRS 12 (Business Rules) and 13 (Roles & Permissions).
 */
public final class AppConstants {

    private AppConstants() {}

    // Job Card workflow (SRS 4.5 FR-JOB-4)
    public static final String JOB_STATUS_RECEIVED = "VEHICLE_RECEIVED";
    public static final String JOB_STATUS_INSPECTION = "INSPECTION";
    public static final String JOB_STATUS_REPAIR_STARTED = "REPAIR_STARTED";
    public static final String JOB_STATUS_QC = "QUALITY_CHECK";
    public static final String JOB_STATUS_DELIVERED = "DELIVERED";

    // Roles (SRS 13)
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_SERVICE_ADVISOR = "SERVICE_ADVISOR";
    public static final String ROLE_MECHANIC = "MECHANIC";
    public static final String ROLE_INVENTORY = "INVENTORY_MANAGER";
    public static final String ROLE_BILLING = "BILLING_USER";
    public static final String ROLE_CUSTOMER = "CUSTOMER";
}
