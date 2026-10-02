package com.autoservicehub.dto;

import com.autoservicehub.entity.BillingItemCategory;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Outbound payload for one estimate line item.
 * Never exposes the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class EstimateItemResponseDTO {
    private Long id;
    private String description;
    private Integer quantity;
    private BigDecimal unitPrice;

    /** quantity × unitPrice, calculated server-side. */
    private BigDecimal lineAmount;

    /**
     * PART | LABOUR | PACKAGE | OTHER. A line written before the category
     * existed is reported as PART.
     */
    private BillingItemCategory category;
}