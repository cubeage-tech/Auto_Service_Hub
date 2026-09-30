package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * Records every customer-facing communication channel event (SRS FR-CRM-4).
 * Channels: CALL, WHATSAPP, EMAIL, REMINDER.
 * Directions: INBOUND, OUTBOUND.
 * FK to customers and optionally to job_cards so communications can be
 * associated with a specific service visit.
 */
@Getter
@Setter
@Entity
@Table(name = "communications", indexes = {
        @Index(name = "idx_comm_customer_id", columnList = "customer_id"),
        @Index(name = "idx_comm_sent_at",    columnList = "sent_at")
})
public class Communication extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Optional — link communication to a specific job card. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    /** CALL | WHATSAPP | EMAIL | REMINDER */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    /** INBOUND | OUTBOUND */
    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
