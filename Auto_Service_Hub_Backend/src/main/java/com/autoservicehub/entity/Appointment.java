package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "appointments")
public class Appointment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Column(name = "service_type")
    private String serviceType;
    @Column(name = "appointment_at")
    private LocalDateTime appointmentAt;
    @Column(name = "pickup_drop")
    private Boolean pickupDrop;
    @Column(name = "status")
    private String status;
    @Column(name = "notes")
    private String notes;

    /**
     * How the booking was taken: WALK_IN, ONLINE or PICKUP_DROP.
     *
     * <p>A plain string rather than an enum so the column stays VARCHAR and no
     * existing row has to be rewritten; {@link #appointmentType} is validated
     * against the supported set by the service on the way in. Null on rows
     * booked before this column existed, which the API returns as null rather
     * than guessing a value for them.
     */
    @Column(name = "appointment_type", length = 40)
    private String appointmentType;

    /**
     * Service bay this appointment is booked into ("Bay 1".."Bay 4").
     *
     * <p>A plain string rather than an enum so the garage can be re-fitted
     * with differently numbered bays without a schema change. Null on rows
     * booked before this column existed.
     */
    @Column(name = "bay_slot", length = 40)
    private String baySlot;

    /**
     * The service advisor who owns this appointment.
     *
     * <p>Points at a {@code users} row holding the SERVICE_ADVISOR role; that
     * is checked when the appointment is saved, not just when it is written.
     * Null means unassigned, which is how pre-existing rows are stored.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advisor_id")
    private User advisor;
}
