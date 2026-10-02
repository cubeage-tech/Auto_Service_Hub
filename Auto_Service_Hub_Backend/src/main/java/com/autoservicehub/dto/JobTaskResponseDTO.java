package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Outbound payload for a repair task (FR-JOB-3 / FR-JOB-5).
 * Never exposes the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class JobTaskResponseDTO {
    private Long id;

    /** The one job card this task belongs to. Always present — tasks cannot be orphans. */
    private Long jobCardId;
    private String jobCardNumber;

    /** The mechanic doing this task, when assigned. */
    private Long mechanicId;
    private String mechanicName;

    private String description;

    /** PENDING | IN_PROGRESS | COMPLETED | CANCELLED. */
    private String status;

    /** Labour cost incurred for this task, normalised to money scale. */
    private BigDecimal labourCost;

    private String workNotes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}