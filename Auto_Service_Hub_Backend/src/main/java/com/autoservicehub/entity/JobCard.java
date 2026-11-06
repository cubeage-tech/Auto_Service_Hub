package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "job_cards")
public class JobCard extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id")
    private Mechanic mechanic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    /**
     * The applied service package this job is being carried out under, when the
     * customer holds one for this vehicle.
     *
     * <p>Optional, so a job card for a vehicle with no applied package is
     * entirely unaffected — the overwhelmingly common case.
     *
     * <p>Held from this side rather than from {@code PackageApplication} because
     * the question the workshop asks is "what is this job covered by?", and a
     * vehicle can accumulate several applications over time while each job card
     * is under exactly one. {@code PackageApplication} is also already the
     * record of a sale, so it gains no back-pointer here.
     *
     * <p>This link carries no behaviour of its own: it does not grant a price,
     * consume parts or mark work complete. It only lets the job card report what
     * the customer is covered for.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_application_id")
    private PackageApplication packageApplication;

    @Column(name = "job_card_number")
    private String jobCardNumber;
    @Column(name = "service_type")
    private String serviceType;
    @Column(name = "complaint", columnDefinition = "TEXT")
    private String complaint;
    @Column(name = "technician_notes", columnDefinition = "TEXT")
    private String technicianNotes;
    @Column(name = "odometer_reading")
    private Integer odometerReading;
    @Column(name = "estimated_delivery")
    private LocalDateTime estimatedDelivery;
    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;
    @Column(name = "status")
    private String status;
    @Column(name = "assigned_date")
    private LocalDateTime assignedDate;
    @Column(name = "completed_date")
    private LocalDateTime completedDate;
}
