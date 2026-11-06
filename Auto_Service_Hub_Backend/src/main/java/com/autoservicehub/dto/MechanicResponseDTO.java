package com.autoservicehub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound payload for Mechanic endpoints (SRS 4.6).
 * Never exposes the JPA entity directly.
 *
 * <p><strong>salary</strong> is populated only when the authenticated caller
 * holds one of the salary-authorized roles (OWNER, MANAGER).  For all
 * other roles it is {@code null} and omitted from the JSON output via
 * {@code @JsonInclude(NON_NULL)}, so it does not appear as a field at all
 * for unauthorized callers.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MechanicResponseDTO {

    private Long id;
    private String employeeCode;
    private String name;
    private String phone;
    private Integer experienceYears;
    private String status;

    /** Mechanic role / job title (SRS 4.6). Null until explicitly set. */
    private String role;

    /** Date the mechanic joined the garage. Null until explicitly set. */
    private LocalDate joiningDate;

    /**
     * Monthly salary — present only for OWNER / MANAGER callers.
     * Null (and omitted from JSON) for all other roles.
     */
    private BigDecimal salary;

    /**
     * Masked Government / Aadhaar ID, e.g. {@code "XXXX XXXX 1234"}.
     * Only present when a Government ID has been stored for this mechanic.
     * The full plaintext and the encrypted blob are NEVER returned.
     * Null/absent (omitted from JSON) when no Government ID has been recorded.
     */
    private String maskedGovernmentId;

    /**
     * Skill names (SRS 4.6).
     * Empty list when no skills have been recorded.
     */
    private List<String> skills;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
