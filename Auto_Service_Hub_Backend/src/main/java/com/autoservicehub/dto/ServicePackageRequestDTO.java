package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ServicePackageRequestDTO {
    @NotBlank
    @Size(max = 100)
    private String name;
    @Size(max = 80)
    private String code;
    @NotBlank
    @Size(max = 80)
    private String type;
    @NotNull
    @DecimalMin("0.00")
    private BigDecimal price;
    @Positive
    private Integer durationMinutes;
    @Positive
    private Integer validityDays;
    @DecimalMin("0.00")
    private BigDecimal gstRate;
    @DecimalMin("0.00")
    private BigDecimal discount;
    private Boolean active;
    private List<@jakarta.validation.Valid PackageItemDTO> items;
}
