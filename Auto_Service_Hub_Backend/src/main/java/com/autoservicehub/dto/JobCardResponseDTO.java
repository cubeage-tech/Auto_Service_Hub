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

    /* ---- Linked service package (display only) ---- */

    /**
     * The applied package this job is carried out under, when one is linked.
     * Null for a job card raised without a package, which is the normal case.
     */
    private Long packageApplicationId;

    /** The linked package's terms, read from the application snapshot. */
    private String packageName;
    private String packageType;
    private String packageDuration;
    private BigDecimal packagePrice;
    private BigDecimal packageGstRate;
    private BigDecimal packageDiscountPercentage;

    /**
     * The SERVICE items the linked package includes, in catalogue order.
     *
     * <p>Presentation only. Nothing here is marked complete or consumed, and an
     * empty list means the linked package bundles no service tasks — not that
     * the job has none outstanding.
     */
    private List<String> packageServices;

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
