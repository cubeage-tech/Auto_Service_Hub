package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'notifications' table (SRS section 8.2 High-Level Entities).
 *
 * <p>An in-app notification addressed to one {@link User}. The recipient is
 * mandatory: a notification nobody owns cannot be listed or marked read, and
 * listing is always scoped to the current user so one user can never read
 * another's notifications.
 *
 * <p>{@code referenceType} / {@code referenceId} point back at whatever raised
 * the notification (e.g. {@code FOLLOWUP} plus the follow-up id) so a client can
 * navigate to it. They carry identifiers only — no customer contact details are
 * copied into a notification, so a notification is safe to show in a list.
 */
@Getter
@Setter
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notification_user_id", columnList = "user_id")
})
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "channel")
    private String channel;
    @Column(name = "title")
    private String title;
    @Column(name = "message")
    private String message;
    @Column(name = "status")
    private String status;

    /** What raised this notification, e.g. {@code FOLLOWUP}. */
    @Column(name = "reference_type", length = 40)
    private String referenceType;

    /** Identifier of the related record — the follow-up id for FOLLOWUP. */
    @Column(name = "reference_id")
    private Long referenceId;

    /**
     * Mapped to the {@code is_read} column: {@code READ} is a reserved word in
     * MySQL and cannot be used unquoted as a column identifier (SRS 8.4 - MySQL
     * design guidelines). The Java field name is unchanged, so the Lombok
     * accessors {@code getRead()} / {@code setRead()} and the existing JSON
     * property {@code read} keep working exactly as before.
     */
    @Column(name = "is_read")
    private Boolean read;
}
