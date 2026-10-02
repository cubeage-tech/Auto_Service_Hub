package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maps to the 'purchases' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One purchase order raised against a {@link Supplier}. {@code totalAmount} is
 * always the server-side sum of the purchase's {@link PurchaseItem} lines — a
 * value sent by the client is never stored.
 *
 * <p>{@code status} moves PENDING -> RECEIVED. Receiving is what raises the
 * IN stock movements (FR-INV-2), so a purchase that already has that status
 * must not be received again.
 */
@Getter
@Setter
@Entity
@Table(name = "purchases", indexes = {
        @Index(name = "idx_purchase_supplier_id", columnList = "supplier_id")
})
public class Purchase extends BaseEntity {

    /** The supplier this order was placed with. Mandatory: a purchase has no meaning without one. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    /** quantity * unitPrice summed over the items, calculated server-side. */
    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    /** PENDING | RECEIVED — see {@code PurchaseServiceImpl}. */
    @Column(name = "status")
    private String status;
}
