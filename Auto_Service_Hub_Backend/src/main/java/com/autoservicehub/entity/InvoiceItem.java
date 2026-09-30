package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'invoice_items' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One billed line on the parent {@link Invoice}. Read through
 * {@code InvoiceItemRepository.findByInvoiceIdOrderByIdAsc}.
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_items", indexes = {
        @Index(name = "idx_invoice_item_invoice_id", columnList = "invoice_id")
})
public class InvoiceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

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
