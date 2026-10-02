package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * Inbound payload for raising a purchase order against a supplier (FR-INV-2).
 *
 * <p>{@code items} is required and must not be empty: a purchase with no lines
 * has no total, nothing to receive and nothing to record, so it is rejected
 * rather than stored as an empty order.
 *
 * <p>{@code purchaseDate} defaults to today when omitted. There is no
 * {@code totalAmount} field — the total is always recalculated server-side
 * from the lines, so a value sent by the client cannot influence it.
 */
@Getter
@Setter
public class PurchaseRequestDTO {

    @NotNull(message = "supplierId is required")
    private Long supplierId;

    /** Optional — defaults to today. */
    private LocalDate purchaseDate;

    @NotEmpty(message = "items must contain at least one purchase item")
    @Valid
    private List<PurchaseItemRequestDTO> items;
}