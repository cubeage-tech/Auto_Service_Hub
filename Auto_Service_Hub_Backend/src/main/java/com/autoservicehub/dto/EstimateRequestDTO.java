package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound payload for Estimate create/update endpoints. Billing - Estimates (SRS 4.9)
 * Extend with the fields listed for the 'Estimate' entity (SRS 8.2/8.3) and add
 * jakarta.validation annotations per SRS 14 (Validation Rules).
 */
@Getter
@Setter
public class EstimateRequestDTO {
    private Long jobCardId;

    @DecimalMin("0.00")
    private BigDecimal subtotal;

    @DecimalMin("0.00")
    private BigDecimal discount;

    @DecimalMin("0.00")
    private BigDecimal tax;

    private BigDecimal total;

    private String status;

    private List<@Valid EstimateItemRequestDTO> items;
}
