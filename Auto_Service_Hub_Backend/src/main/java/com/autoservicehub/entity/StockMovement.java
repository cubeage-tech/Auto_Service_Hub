package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'stock_movements' table (SRS section 8.2 High-Level Entities).
 *
 * <p>Immutable ledger of every change to a part's stock. One row is written by
 * {@code StockMovementService} for each IN, OUT or ADJUSTMENT, and is never
 * edited or deleted afterwards — the running stock on {@link Part} is the
 * cached total, while this table is the audit trail.
 *
 * <p>{@code part} is mandatory: a movement without a part is meaningless.
 * {@code jobCard} is set only for movements raised by a repair (part
 * consumption); a goods receipt or a stock take has no job card.
 *
 * <p>{@code quantity} is always stored as a positive magnitude together with
 * {@code movementType}; the direction of the change is carried by the type
 * (IN increases, OUT decreases) and, for ADJUSTMENT, by
 * {@code adjustmentDelta}. {@code stockBefore}/{@code stockAfter} record the
 * resulting balance so a movement can be understood without replaying history.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_movements", indexes = {
        @Index(name = "idx_stock_movement_part_id", columnList = "part_id"),
        @Index(name = "idx_stock_movement_job_card_id", columnList = "job_card_id")
})
public class StockMovement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    /** Set when the movement is a part consumption against a repair job. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    /** IN | OUT | ADJUSTMENT — see {@code StockMovementService}. */
    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    /** Magnitude of the movement, always positive. */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * Signed change applied by an ADJUSTMENT movement: negative reduces stock,
     * positive increases it. Null for IN and OUT, where the type already
     * implies the direction.
     */
    @Column(name = "adjustment_delta")
    private Integer adjustmentDelta;

    /** Stock on hand immediately before this movement was applied. */
    @Column(name = "stock_before", nullable = false)
    private Integer stockBefore;

    /** Stock on hand immediately after this movement was applied. */
    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter;

    @Column(name = "reason")
    private String reason;

    /**
     * Free-text pointer to the source document, kept as the existing model's
     * field. For a job-card consumption this holds the job card number.
     */
    @Column(name = "reference")
    private String reference;
}
