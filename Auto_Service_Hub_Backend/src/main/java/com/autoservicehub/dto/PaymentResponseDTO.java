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
    private BigDecimal amount;
    private String mode;
    private String transactionRef;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
