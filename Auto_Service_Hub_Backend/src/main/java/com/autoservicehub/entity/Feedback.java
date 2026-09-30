package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Stores customer feedback and star ratings after completed service (SRS FR-CRM-5).
 * Rating range: 1–5.
 * FK to customers (who gave feedback) and job_cards (which service it relates to).
 */
@Getter
@Setter
@Entity
@Table(name = "feedback", indexes = {
        @Index(name = "idx_feedback_customer_id", columnList = "customer_id"),
        @Index(name = "idx_feedback_job_card_id", columnList = "job_card_id")
})
public class Feedback extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id", nullable = false)
    private JobCard jobCard;

    /** Star rating 1–5 (SRS FR-CRM-5). */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;
}
