package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * Inbound payload for a customer follow-up (SRS 4.10).
 *
 * <p>{@code dueDate} is required — a follow-up with no date can never become
 * due, so it could never be reminded about. {@code reason} is required so the
 * reminder says why the workshop is getting in touch.
 */
@Getter
@Setter
public class FollowupRequestDTO {

    @NotNull(message = "customerId is required")
    private Long customerId;

    /** Optional — the service visit this follow-up relates to. */
    private Long jobCardId;

    @NotNull(message = "dueDate is required")
    private LocalDate dueDate;

    @NotBlank(message = "reason is required")
    private String reason;

    /** PENDING | IN_PROGRESS | COMPLETED | CANCELLED. Defaults to PENDING. */
    private String status;
}
