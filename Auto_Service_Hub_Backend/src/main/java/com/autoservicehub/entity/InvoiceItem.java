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
 *
 * <p>{@code category} distinguishes a part from labour, a package or any other
 * charge (FR-BILL-2). It is nullable and {@code null} reads as
 * {@link BillingItemCategory#PART}, so lines written before the column existed
 * keep their meaning and no data migration is needed.
 *
 * <p>{@code jobTask} is the provenance link for a labour line: it records the
 * {@code JobTask} the charge came from, which is what makes it possible to
 * refuse billing the same task's labour twice onto one invoice. It is null for
 * every line that was not generated from a task.
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_items", indexes = {
        @Index(name = "idx_invoice_item_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_invoice_item_job_task_id", columnList = "job_task_id")
})
public class InvoiceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /**
     * The repair task this labour line was generated from, or null for any line
     * entered directly. Never set by the client.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_task_id")
    private JobTask jobTask;

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
