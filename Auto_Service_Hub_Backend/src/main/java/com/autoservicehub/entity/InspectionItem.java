package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'inspection_items' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One row per checklist point inspected on the parent
 * {@link Inspection}, so every finding is attributable to the vehicle
 * inspection that produced it. Items are read through
 * {@code InspectionItemRepository.findByInspectionIdOrderByIdAsc} rather than
 * a {@code @OneToMany} collection, matching the rest of the data model
 * (which navigates parent → child by repository query only).
 */
@Getter
@Setter
@Entity
@Table(name = "inspection_items", indexes = {
        @Index(name = "idx_inspection_item_inspection_id", columnList = "inspection_id")
})
public class InspectionItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    @Column(name = "checklist_item")
    private String checklistItem;
    @Column(name = "finding")
    private String finding;
    @Column(name = "photo_url")
    private String photoUrl;
}
