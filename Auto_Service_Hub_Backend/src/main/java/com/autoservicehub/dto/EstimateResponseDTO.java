package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound payload for Estimate endpoints. Never expose the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class EstimateResponseDTO {
    private Long id;
    private Long jobCardId;
    private String jobCardNumber;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal tax;

    /**
     * The GST percentage actually applied to this document. Null means the
     * configured workshop default was used.
     */
    private BigDecimal gstRate;
    private BigDecimal total;
    private String status;

    /** Free-text remarks saved with this estimate, or null when none were given. */
    private String notes;
    private List<EstimateItemResponseDTO> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
