package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to the 'followups' table (SRS section 8.2 High-Level Entities).
 *
 * <p>Part of the core workflow (… → Billing → Customer Follow-up): a follow-up
 * is a reminder owed to a customer, usually after a service visit.
 *
 * <p>{@code notifiedAt} records when the due-follow-up notification was raised.
 * It is what makes the scheduled processing idempotent: a follow-up is only
 * notified once, however many times the scheduler runs while it stays due.
 */
@Getter
@Setter
@Entity
@Table(name = "followups", indexes = {
        @Index(name = "idx_followup_customer_id", columnList = "customer_id"),
        @Index(name = "idx_followup_job_card_id", columnList = "job_card_id"),
        @Index(name = "idx_followup_due_date", columnList = "due_date")
})
public class Followup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "reason")
    private String reason;
    @Column(name = "status")
    private String status;

    /** When the due-follow-up notification was raised; null while un-notified. */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;
}
