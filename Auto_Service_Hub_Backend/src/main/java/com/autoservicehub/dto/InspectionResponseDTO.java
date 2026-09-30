package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound payload for Inspection endpoints. Never expose the JPA entity directly (SRS 9.1).
 *
 * <p>Carries the vehicle and job-card references so a client can follow the
 * inspection through the core workflow without a second lookup, plus the
 * checklist findings recorded against this inspection.
 */
@Getter
@Setter
public class InspectionResponseDTO {
    private Long id;

    private Long vehicleId;
    private String vehicleInfo;

    private Long jobCardId;
    private String jobCardNumber;

    private String complaint;
    private String technicianNotes;
    private BigDecimal estimatedCost;
    private String status;

    private List<InspectionItemResponseDTO> items;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
