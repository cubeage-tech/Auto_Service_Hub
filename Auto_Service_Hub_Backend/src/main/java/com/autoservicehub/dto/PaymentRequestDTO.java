package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Inbound payload for Payment create/update endpoints. Billing - Payments (SRS 4.9)
 *
 * <p>A payment settles an invoice, so {@code invoiceId} is required — that link
 * is what makes the invoice's outstanding amount computable. Payments are
 * recorded by staff; no gateway integration is implied.
 */
@Getter
@Setter
public class PaymentRequestDTO {

    @NotNull(message = "invoiceId is required")
    private Long invoiceId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than 0")
    private BigDecimal amount;

    /**
     * How the money was handed over. Must be one of {@code UPI}, {@code CASH},
     * {@code CARD} or {@code BANK_TRANSFER} (case-insensitive); anything else is
     * rejected with a 409. Optional — omitted means unrecorded.
     *
     * <p>{@code PAY_LATER} is deliberately not accepted. It describes an
     * invoice that has not been paid yet, not a payment, and recording it would
     * reduce the outstanding balance and could mark the invoice PAID when no
     * money arrived. Leave the invoice PENDING instead.
     */
    private String mode;
    private String transactionRef;
    private String status;
    private LocalDateTime paidAt;
}
