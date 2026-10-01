package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'mechanic_skills' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "mechanic_skills")
public class MechanicSkill extends BaseEntity {

    @Column(name = "skill_name")
    private String skillName;
    @Column(name = "level")
    private String level;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private Mechanic mechanic;
}
