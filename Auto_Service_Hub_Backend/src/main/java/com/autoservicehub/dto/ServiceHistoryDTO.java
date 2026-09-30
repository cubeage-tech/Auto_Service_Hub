package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact job-card summary used in chronological service history (SRS FR-CRM-3).
 * Returned by customer service-history and vehicle service-history endpoints.
 * Ordered newest-first by assignedDate.
 */
@Getter
@Setter
public class ServiceHistoryDTO {

    private Long jobCardId;
    private String jobCardNumber;

    private Long vehicleId;
    private String registrationNo;
    private String vehicleInfo;          // "<regNo> | <model>"

    private Long mechanicId;
    private String mechanicName;

    private String serviceType;
    private String complaint;
    private String technicianNotes;
    private Integer odometerReading;

    private BigDecimal estimatedCost;
    private String status;
    private int progress;                // 1–5 matching job-card workflow

    private LocalDateTime assignedDate;
    private LocalDateTime completedDate;
    private LocalDateTime createdAt;
}
