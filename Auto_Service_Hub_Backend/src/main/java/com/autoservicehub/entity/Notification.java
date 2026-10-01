package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to the 'notifications' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private User recipient;

    @Column(name = "channel")
    private String channel;
    @Column(name = "title")
    private String title;
    @Column(name = "message")
    private String message;
    @Column(name = "status")
    private String status;
    @Column(name = "`read`")
    private Boolean read;
}
