
package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Stores customer feedback and star ratings after completed service.
 * Rating range: 1–5.
 * Foreign keys reference the customer and job card.
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

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id")
    private Mechanic mechanic;
}