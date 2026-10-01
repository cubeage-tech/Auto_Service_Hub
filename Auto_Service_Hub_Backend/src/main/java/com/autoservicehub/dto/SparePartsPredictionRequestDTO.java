package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Inbound payload for AI Spare Parts Prediction
 * (SRS Section 5.5, FR-AI-21..24).
 *
 * <p>{@code serviceDescription} is mandatory: a parts prediction must be tied to
 * a described repair. Without it there is nothing to reason about, and guessing
 * a part list would be meaningless.
 *
 * <p><strong>No client-supplied quantity is accepted as authoritative.</strong>
 * Quantities are either predicted by the provider or reported as unavailable;
 * they are never taken from the request. {@code partIds} narrows the catalogue
 * the provider may recommend from — it cannot force a specific part.
 */
@Getter
@Setter
@Schema(
    description = "Request payload for AI-assisted spare parts prediction (SRS 5.5). " +
                  "Carries no quantity or stock instruction — this endpoint never changes stock."
)
public class SparePartsPredictionRequestDTO {

    @NotBlank(message = "serviceDescription must not be blank")
    @Size(max = 2000, message = "serviceDescription must not exceed 2000 characters")
    @Schema(
        description = "Description of the repair or service the parts are predicted for. Required.",
        example = "Front brake disc and pad replacement, front axle."
    )
    private String serviceDescription;

    @Positive(message = "jobCardId must be a positive number")
    @Schema(
        description = "Optional job card database ID. When supplied, the recorded service type, complaint " +
                      "and technician notes are used as grounding context.",
        example = "7"
    )
    private Long jobCardId;

    @Positive(message = "vehicleId must be a positive number")
    @Schema(
        description = "Optional vehicle database ID. When supplied, make, model, variant and year are read " +
                      "from the database.",
        example = "3"
    )
    private Long vehicleId;

    @Schema(
        description = "Optional list of part database IDs to restrict the prediction to. Narrows the " +
                      "catalogue the provider may recommend from; it cannot force a specific part. " +
                      "When omitted, the whole parts catalogue is offered.",
        example = "[1, 4, 9]"
    )
    private List<Long> partIds;

    @Size(max = 2000, message = "additionalNotes must not exceed 2000 characters")
    @Schema(
        description = "Optional extra notes for the provider, e.g. a part the customer specifically asked about.",
        example = "Customer asked about synthetic vs semi-synthetic oil; prefers OEM brake pads."
    )
    private String additionalNotes;
}
