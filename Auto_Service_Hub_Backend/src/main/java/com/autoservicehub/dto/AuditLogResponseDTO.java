package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for one audit entry (SRS 6 - Auditability).
 * Never exposes the JPA entity directly (SRS 9.1).
 *
 * <p>There is no field here for a credential because none is stored: see
 * {@code AuditServiceImpl}, which scrubs every detail string before it is
 * written.
 */
@Getter
@Setter
public class AuditLogResponseDTO {
    private Long id;
    private String entityName;
    private String entityId;
    private String action;
    private String performedBy;

    /** SUCCESS or FAILURE. */
    private String result;

    /** Null when the call was not an HTTP request. */
    private String ipAddress;

    private String details;

    /** When the entry was written — stamped at persist time. */
    private LocalDateTime timestamp;
}