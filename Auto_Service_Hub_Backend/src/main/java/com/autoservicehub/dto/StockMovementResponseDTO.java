package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for a single stock movement.
 * Never exposes the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class StockMovementResponseDTO {
    private Long id;

    private Long partId;
    private String partSku;
    private String partName;

    /** IN | OUT | ADJUSTMENT. */
    private String movementType;

    private Integer quantity;
    private Integer adjustmentDelta;
    private Integer stockBefore;
    private Integer stockAfter;

    private Long jobCardId;
    private String jobCardNumber;

    private String reason;
    private String reference;
    private LocalDateTime createdAt;
}