package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class VehicleRequestDTO {
    @NotBlank
    private String registrationNo;
    private String make;
    private String model;
    @Pattern(
            regexp = "(?i)^(PETROL|DIESEL|CNG|ELECTRIC|HYBRID|OTHER)$",
            message = "Fuel type must be PETROL, DIESEL, CNG, ELECTRIC, HYBRID, or OTHER")
    private String fuelType;
    @Pattern(
            regexp = "(?i)^(IN_SERVICE|WAITING|DELIVERED)$",
            message = "Status must be IN_SERVICE, WAITING, or DELIVERED")
    private String status;
    private String variant;
    private Integer year;
    private String engineNo;
    private String chassisNo;
    private Integer mileage;
    private LocalDate insuranceExpiry;
    private LocalDate warrantyExpiry;
    private Long customerId;

    @Size(max = 2000, message = "notes must not exceed 2000 characters")
    private String notes;
}
