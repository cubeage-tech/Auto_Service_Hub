package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Outbound payload for Payment endpoints. Never expose the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class PaymentResponseDTO {
    private Long id;
    private Long invoiceId;

    /**
     * The invoice's status after this payment was applied. Derived server-side
     * from the balance, never taken from the request.
     */
    private String invoiceStatus;

    /**
     * What remains owed on the invoice after this payment, recomputed from the
     * invoice total and the successful payments recorded against it.
     */
    private BigDecimal invoiceOutstandingAmount;

    private BigDecimal amount;
    private String mode;
    private String transactionRef;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
