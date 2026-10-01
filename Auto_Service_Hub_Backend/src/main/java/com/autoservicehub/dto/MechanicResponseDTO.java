package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class MechanicResponseDTO {
    private Long id;
    private String employeeCode;
    private String name;
    private String phone;
    private Long userId;
    private Integer experienceYears;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
