package com.autoservicehub.dto;

import com.autoservicehub.entity.BillingItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Inbound payload for a single invoice line item.
 *
 * <p>The parent invoice is taken from the enclosing
 * {@link InvoiceRequestDTO}. Only quantity and unit price are accepted — the
 * line amount is calculated server-side.
 *
 * <p>{@code category} is optional and defaults to
 * {@link BillingItemCategory#PART}, which is what every line meant before the
 * column existed. A labour line entered this way is NOT linked to a
 * {@code JobTask} and so carries no provenance; use
 * {@code POST /api/v1/invoices/{id}/labour} when the charge should come from
 * actual work, so it can be checked against double-billing.
 */
@Getter
@Setter
public class InvoiceItemRequestDTO {

    @NotBlank(message = "description is required")
    private String description;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than 0")
    private Integer quantity;

    @NotNull(message = "unitPrice is required")
    @PositiveOrZero(message = "unitPrice must not be negative")
    private BigDecimal unitPrice;

    /** PART (default) | LABOUR | PACKAGE | OTHER. */
    private BillingItemCategory category;
}