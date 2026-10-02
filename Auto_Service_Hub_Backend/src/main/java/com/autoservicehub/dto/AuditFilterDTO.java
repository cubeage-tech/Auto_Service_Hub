package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Optional filters for the audit read endpoint (SRS 6 - Auditability).
 *
 * <p>Every field is optional and they combine with AND, so an empty filter means
 * "the whole trail" rather than "entries that match nothing".
 *
 * <p>{@code from}/{@code to} form a half-open window, {@code [from, to)},
 * matching the project's other report queries — pass the start of the day after
 * the last day you want, and that day is included whole.
 */
@Getter
@Setter
public class AuditFilterDTO {

    /** e.g. INVOICE, JOB_TASK, PAYMENT. Case-insensitive. */
    private String entityName;

    /** The id of the record, e.g. {@code 42}. */
    private String entityId;

    /** e.g. INVOICE_DELETE. Case-insensitive. */
    private String action;

    /** SUCCESS or FAILURE. Case-insensitive. */
    private String result;

    /** Username — exact match, as usernames are unique. */
    private String performedBy;

    private LocalDateTime from;
    private LocalDateTime to;
}