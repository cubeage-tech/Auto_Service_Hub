package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'estimates' table (SRS section 8.2 High-Level Entities).
 *
 * <p>An estimate is a quote raised against the repair job, so it belongs to a
 * {@link JobCard} — the Billing step of the core workflow
 * (… → Spare Parts → Billing) happens per job, not per customer or vehicle.
 *
 * <p>All money columns are server-calculated from the estimate's line items
 * ({@link EstimateItem}); nothing here is taken from the client. See
 * {@code BillingCalculator}.
 */
@Getter
@Setter
@Entity
@Table(name = "estimates", indexes = {
        @Index(name = "idx_estimate_job_card_id", columnList = "job_card_id")
})
public class Estimate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_card_id", nullable = false)
    private JobCard jobCard;

    /** Sum of the line amounts, calculated server-side. */
    @Column(name = "subtotal")
    private BigDecimal subtotal;

    /** Flat discount amount applied after the subtotal. */
    @Column(name = "discount")
    private BigDecimal discount;

    /** Tax charged on (subtotal - discount), calculated server-side. */
    @Column(name = "tax")
    private BigDecimal tax;

    /**
     * The GST percentage actually applied to this estimate.
     *
     * <p>Recorded for the same reason as {@code Invoice.gstRate}: the quoted
     * rate must stay visible on the document after the configured default moves.
     * Null means the configured default was used.
     */
    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    /** (subtotal - discount) + tax, calculated server-side. */
    @Column(name = "total")
    private BigDecimal total;

    @Column(name = "status")
    private String status;

    /**
     * Free-text remarks typed by the service advisor on the quote itself
     * (FR-BILL-9).
     *
     * <p>Nullable and deliberately not {@code @NotBlank}: most estimates carry
     * no notes, and clearing the box must be a legal edit. A blank string is
     * normalised to {@code null} on the way in (see
     * {@code EstimateServiceImpl.mapToEntity}) so "cleared" and "never filled
     * in" are stored identically.
     *
     * <p>Carried onto the invoice when an estimate is converted, alongside the
     * discount and GST rate, so converting never loses what was written on the
     * quote.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
