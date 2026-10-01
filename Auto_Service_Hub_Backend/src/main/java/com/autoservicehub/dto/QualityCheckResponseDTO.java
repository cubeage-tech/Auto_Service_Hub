package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for quality-check endpoints. Never expose the JPA entity
 * directly (SRS 9.1).
 */
@Getter
@Setter
public class QualityCheckResponseDTO {
    private Long id;
    private Long jobCardId;

    /** PASS or FAIL. */
    private String result;

    private String remarks;

    /** 1-based attempt sequence; the highest value is the governing result. */
    private Integer attemptNo;

    private Long checkedByUserId;
    private String checkedByName;

    private LocalDateTime checkedAt;
    private LocalDateTime createdAt;
}
