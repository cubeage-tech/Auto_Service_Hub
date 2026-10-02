package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class JobCardResponseDTO {
    private Long id;
    private String jobCardNumber;
    private Long customerId;
    private String customerName;
    private Long vehicleId;
    private String vehicleInfo;
    private Long mechanicId;
    private String mechanicName;
    private String serviceType;
    private String complaint;
    private String technicianNotes;

    /**
     * The vehicle inspection this job card was raised from, when one exists.
     * Null for a job card created without an inspection.
     */
    private Long inspectionId;
    private String inspectionStatus;
    private List<InspectionItemResponseDTO> inspectionItems;

    private Integer odometerReading;
    private LocalDateTime estimatedDelivery;
    private BigDecimal estimatedCost;
    private String status;
    private int progress;
    private LocalDateTime assignedDate;
    private LocalDateTime completedDate;

    /**
     * How many repair tasks this job card has (FR-JOB-3).
     *
     * <p>Added alongside the existing fields without displacing any of them, so
     * current clients of this response are unaffected. Zero for a job card with
     * no tasks yet.
     */
    private long taskCount;

    /**
     * This job card's total labour cost, summed server-side from its tasks
     * (FR-JOB-3).
     *
     * <p>Distinct from {@code estimatedCost}, which is the figure quoted to the
     * customer when the job was raised and is whatever the client sent. This is
     * what the work actually cost the workshop, derived from the stored tasks
     * and never from a request. Zero for a job card with no costed tasks.
     */
    private BigDecimal totalLabourCost;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
