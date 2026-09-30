package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for recording a stock movement against a part.
 *
 * <p>{@code movementType} is IN | OUT | ADJUSTMENT.
 * <ul>
 *   <li>IN increases stock by {@code quantity}.</li>
 *   <li>OUT decreases stock by {@code quantity} and fails if stock would go
 *       below zero.</li>
 *   <li>ADJUSTMENT applies {@code adjustmentDelta} (negative to reduce, positive
 *       to increase) and fails if it would take stock below zero.</li>
 * </ul>
 *
 * <p>{@code jobCardId} is optional and links the movement to a repair job.
 */
@Getter
@Setter
public class StockMovementRequestDTO {

    /**
     * Optional here: the endpoint takes the part from the path
     * ({@code POST /api/v1/parts/{id}/stock-movements}) and overwrites this
     * field, so requiring it in the body would reject a perfectly valid
     * request. Ignored when supplied.
     */
    private Long partId;

    /** IN | OUT | ADJUSTMENT. */
    @NotBlank(message = "movementType is required")
    private String movementType;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than 0")
    private Integer quantity;

    /**
     * Signed change for an ADJUSTMENT. Required for ADJUSTMENT and ignored for
     * IN and OUT. Must not be zero.
     */
    private Integer adjustmentDelta;

    /** Optional — link this movement to the repair job that caused it. */
    private Long jobCardId;

    private String reason;

    private String reference;
}