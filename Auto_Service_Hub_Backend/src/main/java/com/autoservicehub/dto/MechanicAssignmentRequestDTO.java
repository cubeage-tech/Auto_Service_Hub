package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Inbound payload for AI Mechanic Assignment (SRS Section 5.4, FR-AI-17..20).
 *
 * <p>{@code jobCardId} is mandatory: the recommendation must be grounded in a
 * real job card (its service type, complaint and current status). Recommending
 * a mechanic for an undefined job would mean inventing both the work and the
 * person, so the request is rejected instead.
 *
 * <p><strong>No client-supplied mechanic is accepted as the answer.</strong>
 * {@code candidateMechanicIds} narrows the candidate pool the service will
 * consider; it cannot force a particular recommendation. A mechanic is only
 * recommended when the provider returns one that exists and is an active
 * candidate.
 */
@Getter
@Setter
@Schema(
    description = "Request payload for AI-assisted mechanic assignment (SRS 5.4). " +
                  "Job card is required — an assignment recommendation is always made for a real job."
)
public class MechanicAssignmentRequestDTO {

    @NotNull(message = "jobCardId is required")
    @Positive(message = "jobCardId must be a positive number")
    @Schema(
        description = "Database ID of the job card a mechanic is recommended for. Required. The job's " +
                      "service type, complaint, status and current assignment are read from the database.",
        example = "7"
    )
    private Long jobCardId;

    @Schema(
        description = "Optional list of mechanic database IDs to restrict the recommendation to. Used to " +
                      "narrow the candidate pool; it cannot force a specific mechanic. When omitted, all " +
                      "active mechanics are considered.",
        example = "[1, 4, 9]"
    )
    private List<Long> candidateMechanicIds;

    @Size(max = 2000, message = "additionalContext must not exceed 2000 characters")
    @Schema(
        description = "Optional extra context for the provider, e.g. a specific skill or certification the " +
                      "work requires. Advisory input only — it is not matched against any skill data, because " +
                      "the system has no usable mechanic skill or certification records.",
        example = "Requires an EV-trained technician for the high-voltage battery work."
    )
    private String additionalContext;
}
