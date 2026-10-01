package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ServicePackageResponseDTO {
    private Long id;
    private String name;
    private String code;
    private String type;
    private BigDecimal price;
    private Integer durationMinutes;
    private Integer validityDays;
    private BigDecimal gstRate;
    private BigDecimal discount;
    private Boolean active;
    private List<PackageItemDTO> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
