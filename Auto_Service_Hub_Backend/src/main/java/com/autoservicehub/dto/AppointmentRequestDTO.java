package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class AppointmentRequestDTO {
    @NotNull
    private Long customerId;
    @NotNull
    private Long vehicleId;
    @NotBlank
    private String serviceType;
    @NotNull
    private LocalDateTime appointmentAt;
    @Size(max = 40)
    private String appointmentType;
    @Size(max = 80)
    private String timeSlot;
    @Size(max = 80)
    private String bay;
    private Long assignedAdvisorId;
    private Boolean pickupDrop;
    @Size(max = 5000)
    private String notes;
    private String status;
}
