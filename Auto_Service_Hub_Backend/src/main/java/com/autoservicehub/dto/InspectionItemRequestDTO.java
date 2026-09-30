package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for a single checklist point recorded during a vehicle
 * inspection. The parent inspection is supplied by the enclosing
 * {@link InspectionRequestDTO} rather than by the client, so a finding can
 * never be attached to an inspection other than the one being saved.
 */
@Getter
@Setter
public class InspectionItemRequestDTO {

    @NotBlank(message = "checklistItem is required")
    private String checklistItem;

    /** What was observed at this checklist point (optional — a point may be ticked with no note). */
    private String finding;

    /** Optional reference to a stored image of the finding. */
    private String photoUrl;
}