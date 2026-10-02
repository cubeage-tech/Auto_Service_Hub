package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for consuming spare parts against a job card during repair.
 *
 * <p>Consumption always produces an OUT stock movement and reduces the part's
 * stock. It fails with 409 if there is not enough stock on hand, and stock can
 * never be driven below zero.
 */
@Getter
@Setter
public class PartConsumptionRequestDTO {

    @NotNull(message = "partId is required")
    private Long partId;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than 0")
    private Integer quantity;

    /** Optional free-text note stored on the resulting movement. */
    private String reason;
}