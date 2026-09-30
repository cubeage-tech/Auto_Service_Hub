package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-REP-3: Parts consumed in a period.
 *
 * <p>Built from StockMovement rows of type {@code OUT} only — goods received
 * (IN) and stock corrections (ADJUSTMENT) are deliberately excluded, because
 * neither represents parts leaving the shelf for a customer.
 *
 * <p>{@code estimatedCost} multiplies the consumed quantity by the part's
 * <em>current</em> {@code purchase_price}. That is an approximation, not a
 * historical cost: the model stores no cost as-of the date the part was used.
 */
@Getter
@Setter
public class PartsUsageReportDTO {

    private LocalDate from;
    private LocalDate to;

    /** Number of distinct parts consumed in the period. */
    private long distinctPartCount;

    /** Total units consumed across all parts (OUT movements only). */
    private long totalQuantityConsumed;

    /** Sum of {@link PartsUsageRowDTO#estimatedCost} across all rows. */
    private BigDecimal totalEstimatedCost = BigDecimal.ZERO;

    private java.util.List<PartsUsageRowDTO> parts = new java.util.ArrayList<>();

    /** Describes how the figures were derived, so a reader is not misled. */
    private String methodology;
}
