package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One part's consumption within a period (FR-REP-3).
 *
 * <p>{@code jobCardNumbers} and {@code mechanicNames} list the jobs and mechanics
 * the part was consumed against, but only where the StockMovement actually
 * recorded them — they are empty when the movement has no job card attached.
 */
@Getter
@Setter
public class PartsUsageRowDTO {

    private Long   partId;
    private String sku;
    private String partName;
    private String unit;

    /** Units consumed via OUT movements in the period. */
    private long quantityConsumed;

    /** Number of OUT movements behind this total. */
    private long movementCount;

    /** Current purchase price of the part, not the historical cost. */
    private BigDecimal currentPurchasePrice;

    /** quantityConsumed x currentPurchasePrice. */
    private BigDecimal estimatedCost;

    /** Distinct job cards the part was consumed against, where recorded. */
    private java.util.List<String> jobCardNumbers = new java.util.ArrayList<>();

    /** Distinct mechanics on those job cards, where recorded. */
    private java.util.List<String> mechanicNames = new java.util.ArrayList<>();
}
