package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class AppointmentResponseDTO {
    private Long id;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private Long vehicleId;
    private String vehicleInfo;
    private String serviceType;
    private LocalDateTime appointmentAt;
    private Boolean pickupDrop;
    private String notes;
    private String status;

    /** Service bay booked into, or null for appointments booked before bays existed. */
    private String baySlot;

    /**
     * Assigned service advisor. {@code advisorId} is null and {@code advisorName}
     * is null together, so a client can treat "unassigned" as one condition.
     */
    private Long advisorId;
    private String advisorName;

    /**
     * Stored booking type (WALK_IN / ONLINE / PICKUP_DROP), or null for
     * appointments booked before the column existed. Clients should render null
     * as unknown rather than defaulting it — the value is echoed exactly as
     * stored so a round-trip through the edit form cannot change it.
     */
    private String appointmentType;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
