package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MechanicRequestDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String employeeCode;
    private String phone;
    private Long userId;
    @Min(0)
    private Integer experienceYears;
    private String status;
}
