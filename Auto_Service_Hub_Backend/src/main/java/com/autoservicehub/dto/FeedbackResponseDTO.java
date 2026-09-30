package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for feedback records (SRS FR-CRM-5).
 * Never exposes the JPA entity directly.
 */
@Getter
@Setter
public class FeedbackResponseDTO {

    private Long id;

    private Long customerId;
    private String customerName;

    private Long jobCardId;
    private String jobCardNumber;
    private String serviceType;

    /** Star rating 1–5. */
    private Integer rating;

    /** Human-readable label derived from rating: 1=Poor … 5=Excellent. */
    private String ratingLabel;

    private String comments;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
