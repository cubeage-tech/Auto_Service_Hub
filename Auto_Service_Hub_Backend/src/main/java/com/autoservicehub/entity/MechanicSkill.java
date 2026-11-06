package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One skill entry for a mechanic (SRS 4.6).
 *
 * <p>Each row belongs to exactly one {@link Mechanic}.  The relationship was
 * missing from the original entity; {@code mechanic_id} was added to the table
 * in migration V4.
 *
 * <p>{@code skillName} is free text (e.g. "Engine", "AC Repair").
 * {@code level}      is free text (e.g. "Beginner", "Advanced") — optional.
 */
@Getter
@Setter
@Entity
@Table(name = "mechanic_skills",
       indexes = @Index(name = "idx_mechanic_skills_mechanic_id", columnList = "mechanic_id"))
public class MechanicSkill extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = true)   // nullable to survive old orphan rows
    private Mechanic mechanic;

    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    @Column(name = "level", length = 50)
    private String level;
}
