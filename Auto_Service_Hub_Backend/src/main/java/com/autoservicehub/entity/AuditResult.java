package com.autoservicehub.entity;

/**
 * Whether an audited action completed (SRS 6 - Auditability).
 *
 * <p>Two values, because an attempt that was refused is as much a part of the
 * story as one that succeeded: a repeated rejected attempt at deleting a
 * payment is exactly what an investigation would be looking for.
 */
public enum AuditResult {

    /** The operation completed and its business transaction committed. */
    SUCCESS,

    /**
     * The operation was attempted and refused or failed.
     *
     * <p>Recorded in its own transaction, so the record survives even though the
     * business transaction it describes was rolled back — which is the whole
     * reason it is worth keeping.
     */
    FAILURE
}