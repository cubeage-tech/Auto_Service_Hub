package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One ordered line on a purchase (FR-INV-2).
 *
 * <p>{@code quantity} must be positive — ordering zero units, or a negative
 * count that would be a disguised return, is not a purchase. {@code unitPrice}
 * may be zero (a free sample or a goodwill credit) but never negative.
 *
 * <p>There is deliberately no {@code lineAmount} or {@code totalAmount} field:
 * the amount is computed from quantity and unit price on the server, so a
 * tampered payload cannot change what the purchase is worth.
 */
@Getter
@Setter
public class PurchaseItemRequestDTO {

    @NotNull(message = "partId is required")
    private Long partId;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than 0")
    private Integer quantity;

    @NotNull(message = "unitPrice is required")
    @PositiveOrZero(message = "unitPrice must not be negative")
    private BigDecimal unitPrice;
}