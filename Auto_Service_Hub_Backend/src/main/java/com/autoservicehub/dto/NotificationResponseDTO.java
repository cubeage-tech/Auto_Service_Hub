package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for one in-app notification.
 * Never exposes the JPA entity directly (SRS 9.1).
 *
 * <p>Carries identifiers for the record that raised it (e.g. the follow-up)
 * rather than copying customer contact details, so a notification list is safe
 * to render without leaking PII.
 */
@Getter
@Setter
public class NotificationResponseDTO {
    private Long id;
    private String channel;
    private String title;
    private String message;
    private String status;

    /** True until the recipient marks it read. */
    private boolean read;

    /** What raised this notification, e.g. {@code FOLLOWUP}. */
    private String referenceType;

    /** Id of the related record — the follow-up id for {@code FOLLOWUP}. */
    private Long referenceId;

    private LocalDateTime createdAt;
}
