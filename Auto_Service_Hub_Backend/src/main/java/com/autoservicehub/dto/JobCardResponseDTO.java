package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

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
    private Set<Long> mechanicIds;
    private Set<String> requiredSkills;
    private String serviceType;
    private String complaint;
    private String technicianNotes;
    private Integer odometerReading;
    private LocalDateTime estimatedDelivery;
    private BigDecimal estimatedCost;
    private String status;
    private int progress;
    private LocalDateTime assignedDate;
    private LocalDateTime startedDate;
    private LocalDateTime completedDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
