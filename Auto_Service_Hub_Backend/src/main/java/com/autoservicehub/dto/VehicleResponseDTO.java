package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
public class VehicleResponseDTO {
    private Long id;
    private String registrationNo;
    private String make;
    private String model;
    private String variant;
    private String fuelType;
    private Integer year;
    private String engineNo;
    private String chassisNo;
    private Integer mileage;
    private LocalDate insuranceExpiry;
    private LocalDate warrantyExpiry;
    private String notes;
    private Long customerId;
    private String customerName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
