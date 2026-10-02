package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for AI Vehicle Diagnosis (SRS Section 5.1, FR-AI-01..04).
 *
 * <p>At least one of {@code symptoms} or {@code inspectionFindings} must be
 * present so the AI has meaningful content to reason over.  Both fields are
 * validated at the service layer (see {@code VehicleDiagnosisServiceImpl}).
 *
 * <p>Linking via {@code vehicleId} is optional but recommended: when supplied
 * the service enriches the request with make/model/year/mileage from the
 * database so the AI can give more relevant output.
 */
@Getter
@Setter
@Schema(description = "Request payload for AI-assisted vehicle diagnosis (SRS 5.1)")
public class DiagnosisRequestDTO {

    @Schema(
        description = "Database ID of the vehicle to be diagnosed. When supplied the service " +
                      "automatically adds make, model, year and mileage to the AI context.",
        example = "3"
    )
    private Long vehicleId;

    @Schema(
        description = "Database ID of a job card linked to this diagnosis, if one already exists.",
        example = "7"
    )
    private Long jobCardId;

    @NotBlank(message = "symptoms must not be blank")
    @Size(max = 2000, message = "symptoms must not exceed 2000 characters")
    @Schema(
        description = "Customer-reported symptoms or observable issues. Required.",
        example = "Engine makes a knocking sound when accelerating above 3000 RPM. " +
                  "Slight vibration felt through the steering wheel."
    )
    private String symptoms;

    @Size(max = 2000, message = "inspectionFindings must not exceed 2000 characters")
    @Schema(
        description = "Technician inspection findings (optional but improves diagnosis accuracy).",
        example = "Low engine oil. Belt tension appears loose. No visible coolant leaks."
    )
    private String inspectionFindings;

    @Size(max = 2000, message = "technicianNotes must not exceed 2000 characters")
    @Schema(
        description = "Additional free-form notes from the mechanic or service advisor (optional).",
        example = "Vehicle last serviced 18 months ago. Customer reports issue started after a long highway drive."
    )
    private String technicianNotes;
}
