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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_advisor_id")
    private User assignedAdvisor;

    @Column(name = "appointment_type")
    private String appointmentType;
    @Column(name = "service_type")
    private String serviceType;
    @Column(name = "appointment_at")
    private LocalDateTime appointmentAt;
    @Column(name = "time_slot")
    private String timeSlot;
    @Column(name = "bay")
    private String bay;
    @Column(name = "pickup_drop")
    private Boolean pickupDrop;
    @Column(name = "status")
    private String status;
    @Column(name = "notes")
    private String notes;
}
