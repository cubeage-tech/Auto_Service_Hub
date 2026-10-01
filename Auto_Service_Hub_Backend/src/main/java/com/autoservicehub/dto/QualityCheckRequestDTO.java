package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for recording a quality check (SRS 4.5 FR-JOB-6, SRS 12 BR-02).
 *
 * <p>There is deliberately no {@code checkedByUserId} field: the checker is always
 * derived from the security context by the service layer so a client cannot record
 * a check on someone else's behalf (SRS 19 Input Security / Audit).
 */
@Getter
@Setter
public class QualityCheckRequestDTO {

    /** {@code PASS} or {@code FAIL}. Validated case-insensitively by the service. */
    @NotBlank(message = "result is required")
    private String result;

    @Size(max = 4000)
    private String remarks;
}
