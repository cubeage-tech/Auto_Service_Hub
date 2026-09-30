package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maps to the 'invoices' table (SRS section 8.2 High-Level Entities).
 *
 * <p>An invoice bills one {@link JobCard} — the Billing step of the core
 * workflow (… → Spare Parts → Billing) is per job. The customer and vehicle are
 * reached through the job card rather than copied, so they cannot drift.
 *
 * <p>All money columns are server-calculated from the invoice's line items
 * ({@link InvoiceItem}); nothing here is taken from the client. See
 * {@code BillingCalculator}.
 */
@Getter
@Setter
@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoice_job_card_id", columnList = "job_card_id")
})
public class Invoice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_card_id", nullable = false)
    private JobCard jobCard;

    /** Sum of the line amounts, calculated server-side. */
    @Column(name = "subtotal")
    private BigDecimal subtotal;

    /** Flat discount amount applied after the subtotal. */
    @Column(name = "discount")
    private BigDecimal discount;

    /** GST charged on (subtotal - discount), calculated server-side. */
    @Column(name = "gst")
    private BigDecimal gst;

    /** (subtotal - discount) + gst, calculated server-side. */
    @Column(name = "total")
    private BigDecimal total;
    @Column(name = "status")
    private String status;
    @Column(name = "invoice_date")
    private LocalDate invoiceDate;
}
