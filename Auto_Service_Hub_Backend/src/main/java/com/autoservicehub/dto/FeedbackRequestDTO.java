package com.autoservicehub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for submitting post-service customer feedback (SRS FR-CRM-5).
 * Rating is 1–5 stars.
 */
@Getter
@Setter
public class FeedbackRequestDTO {

    @NotNull(message = "customerId is required")
    private Long customerId;

    @NotNull(message = "jobCardId is required")
    private Long jobCardId;

    @NotNull(message = "rating is required")
    @Min(value = 1, message = "rating must be at least 1")
    @Max(value = 5, message = "rating must be at most 5")
    private Integer rating;

    private String comments;
}
