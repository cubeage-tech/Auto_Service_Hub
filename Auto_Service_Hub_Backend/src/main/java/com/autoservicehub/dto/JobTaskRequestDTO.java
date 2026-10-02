package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Inbound payload for creating or updating a repair task (FR-JOB-3).
 *
 * <p>The parent job card is NOT part of this payload: it comes from the path
 * ({@code POST /api/v1/job-cards/{id}/tasks}), so a task can never be attached
 * to a job card other than the one being worked on.
 *
 * <p>{@code description} is required — a task nobody can describe is not
 * actionable. {@code labourCost} may be zero (diagnostic or goodwill work) but
 * never negative: labour is a cost the workshop incurs, so a negative figure
 * would either be a data-entry error or a way to manufacture a negative
 * job total.
 *
 * <p>{@code status} and {@code mechanicId} are optional and default sensibly —
 * a new task starts PENDING and unassigned.
 */
@Getter
@Setter
public class JobTaskRequestDTO {

    @NotBlank(message = "description is required")
    private String description;

    /** Optional — the mechanic doing this task. Must exist if given. */
    private Long mechanicId;

    /** PENDING | IN_PROGRESS | COMPLETED | CANCELLED. Defaults to PENDING. */
    private String status;

    @PositiveOrZero(message = "labourCost must not be negative")
    private BigDecimal labourCost;

    /** Optional notes on the work (FR-JOB-5). */
    private String workNotes;
}