package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JobCardRequestDTO {
    @NotNull
    private Long customerId;
    @NotNull
    private Long vehicleId;

    /**
     * Optional — the vehicle inspection this job card is raised from. When
     * supplied, the inspection must belong to {@code vehicleId} and must not
     * already be linked to a different job card.
     */
    private Long inspectionId;

    /**
     * Optional — the applied service package this job is carried out under.
     *
     * <p>When supplied, the application must belong to both {@code vehicleId}
     * and {@code customerId}, so a package applied to somebody else's car can
     * never be attached. Omitted, the job card's existing link is left alone,
     * which is what keeps an ordinary edit from silently dropping it.
     */
    private Long packageApplicationId;

    private Long mechanicId;
    private Long appointmentId;
    @NotBlank
    private String serviceType;
    private String complaint;
    private String technicianNotes;
    private Integer odometerReading;
    private LocalDateTime estimatedDelivery;
    private BigDecimal estimatedCost;
    private String status;
}
