package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps to the 'inspections' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "inspections")
public class Inspection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    @OneToMany(mappedBy = "inspection", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InspectionItem> items = new ArrayList<>();

    @Column(name = "complaint")
    private String complaint;
    @Column(name = "technician_notes")
    private String technicianNotes;
    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;
    @Column(name = "status")
    private String status;
}
