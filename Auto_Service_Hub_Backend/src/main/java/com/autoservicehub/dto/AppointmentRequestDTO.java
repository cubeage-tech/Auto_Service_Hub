package com.autoservicehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class AppointmentRequestDTO {
    @NotNull
    private Long customerId;
    @NotNull
    private Long vehicleId;
    @NotBlank
    private String serviceType;
    @NotNull
    private LocalDateTime appointmentAt;
    private Boolean pickupDrop;
    private String notes;
    private String status;

    /**
     * Service bay this appointment is booked into, e.g. "Bay 1".
     * Validated against the garage's bay list by AppointmentServiceImpl.
     * Null leaves the bay unset.
     */
    private String baySlot;

    /**
     * Id of the service advisor who owns this appointment. Must reference an
     * active user holding the SERVICE_ADVISOR role — an arbitrary user id is
     * rejected. Null leaves the appointment unassigned.
     */
    private Long advisorId;

    /**
     * How the booking was taken: WALK_IN, ONLINE or PICKUP_DROP.
     * Validated against that set by AppointmentServiceImpl; anything else is
     * rejected rather than stored. Null leaves it unset.
     */
    private String appointmentType;
}
