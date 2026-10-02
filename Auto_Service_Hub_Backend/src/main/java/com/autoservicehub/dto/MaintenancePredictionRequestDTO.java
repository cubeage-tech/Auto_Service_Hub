package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for AI Maintenance Prediction (SRS Section 5.3, FR-AI-09..12).
 *
 * <p>{@code vehicleId} is mandatory: a maintenance prediction is inherently
 * per-vehicle, and without the vehicle there is no mileage, age or service
 * history to reason over. Predicting for "some vehicle" would mean inventing
 * data, so the request is rejected instead.
 *
 * <p>No client-supplied mileage is accepted as authoritative. The stored
 * odometer is read from the database; {@code currentMileage} is an optional
 * advisory override a service advisor may supply when the vehicle has just come
 * in on a newer reading, and is passed to the provider as context only.
 */
@Getter
@Setter
@Schema(
    description = "Request payload for AI-assisted maintenance prediction (SRS 5.3). " +
                  "Vehicle is required — a prediction is always made for a specific vehicle."
)
public class MaintenancePredictionRequestDTO {

    @NotNull(message = "vehicleId is required")
    @Positive(message = "vehicleId must be a positive number")
    @Schema(
        description = "Database ID of the vehicle to predict maintenance for. Required. Service history, " +
                      "last serviced date and odometer readings are read from the database for this vehicle.",
        example = "3"
    )
    private Long vehicleId;

    @Positive(message = "currentMileage must be a positive number")
    @Schema(
        description = "Optional current odometer reading (km) supplied by the service advisor. Used as " +
                      "context only; the stored vehicle mileage is still reported as the authoritative " +
                      "value in dataLimitations when the two differ.",
        example = "54000"
    )
    private Integer currentMileage;

    @Size(max = 2000, message = "notes must not exceed 2000 characters")
    @Schema(
        description = "Optional free-form notes, e.g. customer complaints about unusual noises or usage.",
        example = "Customer reports occasional hesitation during acceleration. Towing occasionally."
    )
    private String notes;
}
