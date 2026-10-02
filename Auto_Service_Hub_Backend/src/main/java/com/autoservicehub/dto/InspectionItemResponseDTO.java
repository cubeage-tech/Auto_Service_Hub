package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for one checklist finding on a vehicle inspection.
 * Never exposes the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class InspectionItemResponseDTO {
    private Long id;
    private String checklistItem;
    private String finding;
    private String photoUrl;
    private LocalDateTime createdAt;
}