package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'inspections' table (SRS section 8.2 High-Level Entities).
 *
 * <p>Part of the core workflow
 * (Customer Booking → Vehicle Inspection → Job Card → Repair → Spare Parts → Billing).
 *
 * <p>An inspection is always performed on a specific vehicle, so {@code vehicle}
 * is mandatory. It is linked to a job card only once the inspection is
 * accepted and the repair work is raised — an inspection may legitimately
 * exist with no job card (for example a walk-around check), so {@code jobCard}
 * is optional. A given job card is produced by at most one inspection; that
 * invariant is enforced in {@code InspectionServiceImpl}, not by a unique
 * constraint, so that the same rule is applied identically whether the link is
 * made from the inspection side or the job-card side.
 */
@Getter
@Setter
@Entity
@Table(name = "inspections", indexes = {
        @Index(name = "idx_inspection_vehicle_id", columnList = "vehicle_id"),
        @Index(name = "idx_inspection_job_card_id", columnList = "job_card_id")
})
public class Inspection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    @Column(name = "complaint")
    private String complaint;
    @Column(name = "technician_notes")
    private String technicianNotes;
    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;
    @Column(name = "status")
    private String status;
}
