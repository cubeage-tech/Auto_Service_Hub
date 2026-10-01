package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class JobTaskCreateRequestDTO {
    @NotBlank
    private String description;
    @NotNull
    private Long mechanicId;
    private BigDecimal labourCost;
}