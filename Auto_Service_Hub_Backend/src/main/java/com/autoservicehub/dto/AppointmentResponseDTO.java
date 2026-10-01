package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class AppointmentResponseDTO {
    private Long id;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private Long vehicleId;
    private String vehicleInfo;
    private String serviceType;
    private LocalDateTime appointmentAt;
    private String appointmentType;
    private String timeSlot;
    private String bay;
    private Long assignedAdvisorId;
    private String assignedAdvisorName;
    private Boolean pickupDrop;
    private String notes;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
