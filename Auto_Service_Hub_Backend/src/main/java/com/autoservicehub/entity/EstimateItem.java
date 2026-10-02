package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'estimate_items' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One quoted line on the parent {@link Estimate}, so every line is
 * attributable to the estimate that produced it. Read through
 * {@code EstimateItemRepository.findByEstimateIdOrderByIdAsc} rather than a
 * {@code @OneToMany} collection, matching the rest of the data model.
 *
 * <p>{@code category} carries the same part/labour/package distinction as
 * {@link InvoiceItem} (FR-BILL-2), so a quote distinguishes a part from a
 * labour line before either becomes an invoice. It is nullable and {@code null}
 * reads as {@link BillingItemCategory#PART}, so existing estimates need no data
 * migration.
 *
 * <p>There is deliberately no {@code jobTask} link here. An estimate is a quote
 * drawn up before the work is done, so it may quote labour for work that has no
 * {@code JobTask} yet; the link only makes sense once real work has been
 * performed and is being billed.
 */
@Getter
@Setter
@Entity
@Table(name = "estimate_items", indexes = {
        @Index(name = "idx_estimate_item_estimate_id", columnList = "estimate_id")
})
public class EstimateItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "estimate_id", nullable = false)
    private Estimate estimate;

    @Column(name = "description")
    private String description;
    @Column(name = "quantity")
    private Integer quantity;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** quantity * unitPrice, calculated server-side and never taken from the client. */
    @Column(name = "line_amount")
    private BigDecimal lineAmount;

    /** PART | LABOUR | PACKAGE | OTHER. Null on legacy rows and read as PART. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private BillingItemCategory category;
}
