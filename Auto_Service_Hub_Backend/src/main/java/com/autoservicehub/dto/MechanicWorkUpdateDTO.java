package com.autoservicehub.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class MechanicWorkUpdateDTO {
    private String status;

    @Size(max = 5000)
    private String mechanicNotes;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal actualCost;
}