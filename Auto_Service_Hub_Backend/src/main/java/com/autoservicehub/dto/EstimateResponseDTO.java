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
    private BigDecimal total;
    private String status;
    private List<EstimateItemResponseDTO> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
