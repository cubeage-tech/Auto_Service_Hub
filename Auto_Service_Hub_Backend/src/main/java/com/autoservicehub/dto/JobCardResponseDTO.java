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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
