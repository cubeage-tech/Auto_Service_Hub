package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound payload for Inspection create/update endpoints. Vehicle Inspection (SRS 4.4)
 *
 * <p>An inspection must name the vehicle it was carried out on, so
 * {@code vehicleId} is required. {@code jobCardId} is optional because the
 * inspection is raised <em>before</em> the job card in the core workflow; it is
 * supplied when the inspection is recorded straight against an existing job
 * card.
 */
@Getter
@Setter
public class InspectionRequestDTO {

    @NotNull(message = "vehicleId is required")
    private Long vehicleId;

    /** Optional — link this inspection to the job card that will carry out the work. */
    private Long jobCardId;

    @NotBlank(message = "complaint is required")
    private String complaint;

    private String technicianNotes;

    @PositiveOrZero(message = "estimatedCost must not be negative")
    private BigDecimal estimatedCost;

    /** PENDING | IN_PROGRESS | COMPLETED. Defaults to PENDING when omitted. */
    private String status;

    /**
     * Checklist findings recorded during the inspection. When supplied on
     * update, the stored findings are replaced by this list; when omitted,
     * existing findings are left untouched.
     */
    @Valid
    private List<InspectionItemRequestDTO> items;
}
