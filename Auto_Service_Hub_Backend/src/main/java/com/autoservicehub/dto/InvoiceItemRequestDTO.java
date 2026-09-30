package com.autoservicehub.dto;

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
}