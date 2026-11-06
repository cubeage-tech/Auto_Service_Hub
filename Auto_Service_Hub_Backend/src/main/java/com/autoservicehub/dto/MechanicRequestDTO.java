package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inbound payload for Mechanic create/update (SRS 4.6).
 *
 * <p><strong>employeeCode</strong> — optional on create; server generates
 * {@code EMP-XXXXXX} when absent.  On update, blank/null preserves the
 * existing code.
 *
 * <p><strong>skills</strong> — full-replace when non-null; null means "leave
 * unchanged" on update and "no skills" on create.
 *
 * <p><strong>salary</strong> — write-access follows the controller's role
 * guard (OWNER/MANAGER/SERVICE_ADVISOR).  The service layer reads only
 * the value supplied; authorization of who may write is enforced by the
 * controller's {@code @PreAuthorize}.
 */
@Getter
@Setter
public class MechanicRequestDTO {

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    /**
     * Optional on create — server generates {@code EMP-XXXXXX} if absent.
     * On update, leave blank/null to keep the existing code unchanged.
     */
    @Size(max = 30, message = "employeeCode must not exceed 30 characters")
    private String employeeCode;

    @Size(max = 30, message = "phone must not exceed 30 characters")
    private String phone;

    @Min(value = 0, message = "experienceYears must be 0 or greater")
    private Integer experienceYears;

    /** ACTIVE or INACTIVE.  Defaults to ACTIVE on create. */
    private String status;

    /**
     * Mechanic role / job title (SRS 4.6).
     * Allowed values: Junior Mechanic, Mechanic, Senior Mechanic,
     * Auto Electrician, Body Shop Technician, Supervisor.
     */
    @Size(max = 60, message = "role must not exceed 60 characters")
    private String role;

    /** Date the mechanic joined the garage. */
    private LocalDate joiningDate;

    /**
     * Monthly salary (SRS 4.6).  Must be ≥ 0 when supplied.
     * DECIMAL(12,2) — no floating-point.
     */
    @DecimalMin(value = "0.0", inclusive = true, message = "salary must not be negative")
    @Digits(integer = 10, fraction = 2, message = "salary must have at most 10 integer and 2 decimal digits")
    private BigDecimal salary;

    /**
     * Skill names to associate (SRS 4.6).
     * Full-replace when non-null; empty list clears all skills.
     */
    @Schema(description = "List of skill names. Replaces existing skills when provided.",
            example = "[\"Engine Repair\",\"AC Repair\"]")
    private List<String> skills;

    /**
     * Optional Government / Aadhaar ID (SRS 4.6).
     * Accepted formats: 12-digit Aadhaar (with or without spaces/dashes),
     * 10-char PAN, or any alphanumeric ID of up to 20 characters.
     *
     * <p>The value is encrypted server-side before storage using AES-GCM
     * (see GovernmentIdEncryptionService).  It is never logged, never echoed
     * back in responses, and never included in audit trails in plaintext.
     *
     * <p>On edit, leave this field null or blank to keep the existing stored
     * value unchanged.  Explicitly sending an empty string also preserves it.
     * Sending a new non-blank value replaces it.
     *
     * <p>Only OWNER, MANAGER and SERVICE_ADVISOR may submit this field
     * (enforced by the controller's @PreAuthorize on write endpoints).
     */
    @Schema(description = "Optional Government / Aadhaar ID. Encrypted before storage. "
                        + "Null/blank on update preserves the existing value.",
            example = "1234 5678 9012")
    @Size(max = 20, message = "governmentId must not exceed 20 characters after removing spaces/dashes")
    @Pattern(regexp = "^[A-Z0-9\\s\\-]{0,20}$",
             flags = Pattern.Flag.CASE_INSENSITIVE,
             message = "governmentId must contain only alphanumeric characters, spaces, or dashes")
    private String governmentId;
}
