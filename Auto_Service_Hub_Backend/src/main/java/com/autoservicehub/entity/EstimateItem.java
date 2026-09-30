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
}
