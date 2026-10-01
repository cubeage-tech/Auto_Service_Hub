package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class VehicleRequestDTO {
    @NotBlank
    @Size(max = 50)
    private String registrationNo;
    private String make;
    private String model;
    private String variant;
    private String fuelType;
    @Positive
    private Integer year;
    private String engineNo;
    private String chassisNo;
    @Min(0)
    private Integer mileage;
    private LocalDate insuranceExpiry;
    private LocalDate warrantyExpiry;
    @Size(max = 5000)
    private String notes;
    private Long customerId;
}
