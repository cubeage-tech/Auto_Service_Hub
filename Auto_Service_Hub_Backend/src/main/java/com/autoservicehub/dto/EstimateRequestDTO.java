package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

    private String status;

    /** Quoted lines. At least one is required — an empty estimate has no value. */
    @NotEmpty(message = "at least one line item is required")
    @Valid
    private List<EstimateItemRequestDTO> items;
}
