package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'purchase_items' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One ordered line on a {@link Purchase}: a quantity of one {@link Part} at
 * the price the supplier charged. {@code lineAmount} is
 * {@code quantity * unitPrice} calculated server-side, so what the purchase
 * was actually worth can be re-checked later without trusting the request.
 *
 * <p>Read through {@code PurchaseItemRepository.findByPurchaseIdOrderByIdAsc}
 * rather than a {@code @OneToMany} collection, matching the rest of the model.
 */
@Getter
@Setter
@Entity
@Table(name = "purchase_items", indexes = {
        @Index(name = "idx_purchase_item_purchase_id", columnList = "purchase_id"),
        @Index(name = "idx_purchase_item_part_id",     columnList = "part_id")
})
public class PurchaseItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    /** The part being bought. Mandatory: an item with no part cannot be received into stock. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** quantity * unitPrice, calculated server-side and never taken from the client. */
    @Column(name = "line_amount")
    private BigDecimal lineAmount;
}
