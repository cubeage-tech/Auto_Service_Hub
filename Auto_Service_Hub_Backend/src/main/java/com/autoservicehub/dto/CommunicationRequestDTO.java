package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Inbound payload for logging a customer communication (SRS FR-CRM-4).
 * channel:    CALL | WHATSAPP | EMAIL | REMINDER
 * direction:  INBOUND | OUTBOUND
 */
@Getter
@Setter
public class CommunicationRequestDTO {

    @NotNull(message = "customerId is required")
    private Long customerId;

    /** Optional — link communication to a specific job card. */
    private Long jobCardId;

    /** CALL | WHATSAPP | EMAIL | REMINDER */
    @NotBlank(message = "channel is required")
    private String channel;

    /** INBOUND | OUTBOUND */
    @NotBlank(message = "direction is required")
    private String direction;

    /** Email subject or call summary title (optional). */
    private String subject;

    @NotBlank(message = "message is required")
    private String message;

    /** Defaults to now() in service if not supplied. */
    private LocalDateTime sentAt;
}
