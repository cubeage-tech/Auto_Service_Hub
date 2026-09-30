package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to the 'payments' table (SRS section 8.2 High-Level Entities).
 *
 * <p>A payment settles an {@link Invoice}. The link is what makes an invoice's
 * outstanding amount computable: total minus the sum of its SUCCESSFUL
 * payments. Recorded here only — no gateway integration, no callback
 * verification; a payment is simply entered by staff.
 */
@Getter
@Setter
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_invoice_id", columnList = "invoice_id")
})
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @Column(name = "amount")
    private BigDecimal amount;
    @Column(name = "mode")
    private String mode;
    @Column(name = "transaction_ref")
    private String transactionRef;
    @Column(name = "status")
    private String status;
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
