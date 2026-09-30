package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for communication records (SRS FR-CRM-4).
 * Never exposes the JPA entity directly.
 */
@Getter
@Setter
public class CommunicationResponseDTO {

    private Long id;

    private Long customerId;
    private String customerName;
    private String customerPhone;

    private Long jobCardId;
    private String jobCardNumber;

    private String channel;
    private String direction;
    private String subject;
    private String message;
    private LocalDateTime sentAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
