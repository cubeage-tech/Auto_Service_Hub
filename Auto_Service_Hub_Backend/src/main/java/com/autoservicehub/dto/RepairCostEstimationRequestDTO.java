package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Inbound payload for AI Repair Cost Estimation (SRS Section 5.2, FR-AI-05..08).
 *
 * <p><strong>Client-supplied financial totals are deliberately NOT accepted.</strong>
 * There is no {@code total}, {@code amount} or {@code price} field on this DTO.
 * An estimate is produced by the AI provider and is advisory only; the
 * authoritative customer-facing price is the estimate/invoice document, which
 * is calculated server-side under SRS Section 12. This DTO therefore cannot be
 * used to inject an arbitrary total into the system.
 *
 * <p>{@code serviceDescription} is the only mandatory field — it is the text the
 * provider reasons over. Everything else is optional domain context that is
 * resolved from the database when supplied.
 */
@Getter
@Setter
@Schema(
    description = "Request payload for AI-assisted repair cost estimation (SRS 5.2). " +
                  "Accepts no client-supplied prices or totals."
)
public class RepairCostEstimationRequestDTO {

    @Schema(
        description = "Database ID of the vehicle being repaired. When supplied, make/model/year/mileage " +
                      "are read from the database and used to ground the estimate.",
        example = "3"
    )
    @Positive(message = "vehicleId must be a positive number")
    private Long vehicleId;

    @Schema(
        description = "Database ID of an existing job card. When supplied, the recorded service type, " +
                      "complaint and technician notes are used as grounding context.",
        example = "7"
    )
    @Positive(message = "jobCardId must be a positive number")
    private Long jobCardId;

    @Schema(
        description = "Database IDs of parts expected to be required. Used to ground the parts component " +
                      "of the estimate with real SKU, selling price and current stock. Optional.",
        example = "[1, 4, 9]"
    )
    private List<Long> partIds;

    @Schema(
        description = "Expected labour hours for the repair. Passed to the provider as context only — the " +
                      "system currently has no labour-rate table, so labour cost cannot be computed " +
                      "authoritatively and this value is not converted to money here.",
        example = "2.5"
    )
    @Positive(message = "labourHours must be greater than 0")
    private java.math.BigDecimal labourHours;

    @NotBlank(message = "serviceDescription must not be blank")
    @Size(max = 2000, message = "serviceDescription must not exceed 2000 characters")
    @Schema(
        description = "Description of the repair work being costed. Required.",
        example = "Front brake disc and pad replacement, front axle."
    )
    private String serviceDescription;

    @Size(max = 2000, message = "inspectionFindings must not exceed 2000 characters")
    @Schema(
        description = "Technician inspection findings. Note: the inspections table has no link to a vehicle " +
                      "or job card yet, so findings must be supplied as free text.",
        example = "Front pads worn to backing plate. Disc scored. Rear pads at 40 percent."
    )
    private String inspectionFindings;

    @Size(max = 2000, message = "technicianNotes must not exceed 2000 characters")
    @Schema(
        description = "Additional free-form notes for the provider (optional).",
        example = "Vehicle last serviced 18 months ago. Customer declined ceramic coating."
    )
    private String technicianNotes;
}
