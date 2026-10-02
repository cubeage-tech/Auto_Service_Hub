package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for AI Damage Detection (SRS Section 5.6, FR-AI-13..16).
 *
 * <p><strong>Text-based assessment — NOT image or vision analysis.</strong>
 * The current backend has no image upload, storage or vision capability, and the
 * only configured AI provider is the no-op client. This DTO therefore accepts
 * written descriptions of damage only. It deliberately has no {@code image},
 * {@code imageUrl}, {@code filename} or storage-reference field, because no such
 * infrastructure exists to validate one against and accepting it would imply a
 * capability the system does not have.
 *
 * <p>{@code vehicleId} and {@code damageDescription} are both mandatory: a
 * damage assessment is always made for a specific vehicle, and it must be
 * grounded in something actually reported.
 */
@Getter
@Setter
@Schema(
    description = "Request payload for AI-assisted damage assessment (SRS 5.6). " +
                  "Text-based only — this endpoint does not analyse photographs or images."
)
public class DamageDetectionRequestDTO {

    @NotNull(message = "vehicleId is required")
    @Positive(message = "vehicleId must be a positive number")
    @Schema(
        description = "Database ID of the vehicle the reported damage relates to. Required. Make, model, " +
                      "year and mileage are read from the database to ground the assessment.",
        example = "3"
    )
    private Long vehicleId;

    @Positive(message = "jobCardId must be a positive number")
    @Schema(
        description = "Optional job card database ID. When supplied it must belong to the same vehicle, and " +
                      "its recorded service type, complaint and technician notes are used as context.",
        example = "7"
    )
    private Long jobCardId;

    @NotBlank(message = "damageDescription must not be blank")
    @Size(max = 4000, message = "damageDescription must not exceed 4000 characters")
    @Schema(
        description = "Written description of the damage that was reported. Required — this is the primary " +
                      "input, since no photographs or images can be submitted or analysed.",
        example = "Front-left wing panel is scraped and dented. Bumper cover is cracked and hanging loose. " +
                  "Left tail light is shattered."
    )
    private String damageDescription;

    @Size(max = 4000, message = "inspectionFindings must not exceed 4000 characters")
    @Schema(
        description = "Optional inspection findings in free text. Note: inspection records are not linked to " +
                      "a vehicle or job card in the current data model, so findings must be supplied here " +
                      "rather than being retrieved from the inspections table.",
        example = "Underbody shows scraping along the sill. Both front suspension arms appear bent."
    )
    private String inspectionFindings;
}
