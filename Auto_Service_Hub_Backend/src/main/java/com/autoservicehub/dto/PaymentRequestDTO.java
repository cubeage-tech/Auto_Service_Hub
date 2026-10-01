package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Inbound payload for Payment create/update endpoints. Billing - Payments (SRS 4.9)
 * Extend with the fields listed for the 'Payment' entity (SRS 8.2/8.3) and add
 * jakarta.validation annotations per SRS 14 (Validation Rules).
 */
@Getter
@Setter
public class PaymentRequestDTO {
    private Long invoiceId;
    private BigDecimal amount;
    private String mode;
    private String transactionRef;
    private String status;
    private LocalDateTime paidAt;
}
