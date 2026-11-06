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

    /**
     * The estimate this invoice was converted from, or null for one raised
     * directly.
     *
     * <p>The column is UNIQUE, which is what makes "convert an estimate twice"
     * impossible at the database level rather than only in application code: two
     * concurrent conversions of the same estimate cannot both commit. Existing
     * invoices all carry null here, and SQL permits any number of nulls in a
     * unique column, so no existing record is affected and no backfill is needed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id", unique = true)
    private Estimate estimate;

    /** Sum of the line amounts, calculated server-side. */
    @Column(name = "subtotal")
    private BigDecimal subtotal;

    /** Flat discount amount applied after the subtotal. */
    @Column(name = "discount")
    private BigDecimal discount;

    /** GST charged on (subtotal - discount), calculated server-side. */
    @Column(name = "gst")
    private BigDecimal gst;

    /**
     * The GST percentage actually applied to this invoice.
     *
     * <p>Stored rather than recomputed on read so the document always shows the
     * rate it was billed at, even if {@code app.billing.tax-rate-percent} is
     * changed later. Null means the configured default was used.
     */
    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    /** (subtotal - discount) + gst, calculated server-side. */
    @Column(name = "total")
    private BigDecimal total;
    @Column(name = "status")
    private String status;
    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    /**
     * Free-text remarks typed by the service advisor on the invoice itself
     * (FR-BILL-9).
     *
     * <p>Nullable and deliberately not {@code @NotBlank}: an invoice with no
     * notes is the common case, and clearing the box must be a legal edit. A
     * blank string is normalised to {@code null} on the way in (see
     * {@code InvoiceServiceImpl.mapToEntity}) so "cleared" and "never filled
     * in" are stored identically rather than as a row of empty strings.
     *
     * <p>Kept as TEXT rather than a bounded VARCHAR because there is nothing
     * arithmetic about it and nothing to index.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
