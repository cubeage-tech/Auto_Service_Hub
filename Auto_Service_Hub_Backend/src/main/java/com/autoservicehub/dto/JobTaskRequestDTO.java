package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobTaskRequestDTO {
    @NotBlank
    private String status;
}