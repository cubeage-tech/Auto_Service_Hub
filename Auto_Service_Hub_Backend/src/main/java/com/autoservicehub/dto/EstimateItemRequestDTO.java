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
 * Inbound payload for a single estimate line item.
 *
 * <p>The parent estimate is taken from the enclosing
 * {@link EstimateRequestDTO}, so a line can never be attached to a different
 * estimate than the one being saved.
 *
 * <p>Only quantity and unit price are accepted — the line amount is calculated
 * server-side, so a client cannot influence the total.
 *
 * <p>{@code category} is optional and defaults to
 * {@link BillingItemCategory#PART}, so an existing client's payload behaves
 * exactly as it did before the column existed. Setting it to
 * {@link BillingItemCategory#LABOUR} lets a quote distinguish technician time
 * from a part, which is what FR-BILL-2 asks for.
 */
@Getter
@Setter
public class EstimateItemRequestDTO {

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