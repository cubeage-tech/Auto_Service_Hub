package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound payload for Estimate create/update endpoints. Billing - Estimates (SRS 4.9)
 *
 * <p>An estimate belongs to the repair job it quotes, so {@code jobCardId} is
 * required. The estimate's value is defined by its line items: {@code subtotal},
 * {@code tax} and {@code total} are <strong>calculated on the server</strong> and
 * any values sent for them are ignored. Only {@code discount} is a client
 * input, because a discount is a decision rather than a computation — and even
 * that is validated against the calculated subtotal.
 */
@Getter
@Setter
public class EstimateRequestDTO {

    @NotNull(message = "jobCardId is required")
    private Long jobCardId;

    /** Flat discount amount. Must not be negative or exceed the subtotal. */
    private BigDecimal discount;

    /** Accepted for backwards compatibility but IGNORED — the server recalculates. */
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;

    /**
     * GST percentage to apply to this estimate, e.g. {@code 18}, {@code 5},
     * {@code 12} or {@code 28}.
     *
     * <p>Null means "use the workshop default"
     * ({@code app.billing.tax-rate-percent}). A supplied rate is validated to
     * 0-100; the tax and total are still calculated server-side.
     */
    private BigDecimal gstRate;

    private String status;

    /**
     * Optional free-text remarks shown on the quote (FR-BILL-9).
     *
     * <p>Optional, and blank is treated as "no notes" rather than as an error.
     * Bound at 2000 characters, matching the other free-text notes fields in
     * this project, so an oversized note is rejected at the edge instead of
     * being silently truncated by the column.
     */
    @Size(max = 2000, message = "notes must not exceed 2000 characters")
    private String notes;

    /** Quoted lines. At least one is required — an empty estimate has no value. */
    @NotEmpty(message = "at least one line item is required")
    @Valid
    private List<EstimateItemRequestDTO> items;
}
