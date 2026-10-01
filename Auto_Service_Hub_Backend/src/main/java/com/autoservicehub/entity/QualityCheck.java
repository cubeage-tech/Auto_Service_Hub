package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Quality-control result recorded against a job card (SRS 4.5 FR-JOB-6,
 * SRS 12 BR-02 "a vehicle cannot be delivered while mandatory repair/QC
 * information is incomplete").
 *
 * <p><strong>History, not overwrite:</strong> every attempt is stored as its own
 * row. A later attempt supersedes an earlier one, so a stale PASS can never mask a
 * newer FAIL. Consumers must read the attempt with the highest
 * {@code attemptNo} for a job card rather than "any PASS exists".
 *
 * <p><strong>Indexes.</strong> No {@code @Index} is declared here on purpose. Every
 * read path filters on {@code job_card_id} and either orders or aggregates on
 * {@code attempt_no}, which the {@code UNIQUE (job_card_id, attempt_no)} constraint
 * already covers via its leftmost prefix. Declaring an additional index here would
 * only duplicate it. See {@code db/phase3_additive.sql}.
 *
 * <p>The checker is always derived from the security context by the service layer;
 * a client-supplied checker id is never trusted.
 */
@Getter
@Setter
@Entity
@Table(name = "quality_checks")
public class QualityCheck extends BaseEntity {

    /** Passing quality check. */
    public static final String RESULT_PASS = "PASS";

    /** Failing quality check; requires rework before delivery. */
    public static final String RESULT_FAIL = "FAIL";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_card_id", nullable = false)
    private JobCard jobCard;

    /** {@code PASS} or {@code FAIL}. */
    @Column(name = "result", nullable = false, length = 10)
    private String result;

    /** Free-text findings / reason for the result. */
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /** The authenticated user who recorded this check. Never taken from the request. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checked_by_user_id", nullable = false)
    private User checkedBy;

    /** Explicit check time; {@code BaseEntity.createdAt} is also populated. */
    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    /**
     * 1-based sequence of this attempt for the job card. Computed server-side as
     * {@code max(existing) + 1} inside the recording transaction.
     */
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    public boolean isPass() {
        return RESULT_PASS.equalsIgnoreCase(result);
    }
}
