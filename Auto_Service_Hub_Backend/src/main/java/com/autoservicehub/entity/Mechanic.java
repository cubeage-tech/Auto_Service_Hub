package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps to the 'mechanics' table (SRS 8.2, 4.6).
 *
 * <p>Skills are managed via the child {@link MechanicSkill} collection.
 * Migration V4 added the {@code mechanic_id} FK to {@code mechanic_skills}.
 * Migration V5 added {@code role}, {@code joining_date}, and {@code salary}.
 */
@Getter
@Setter
@Entity
@Table(name = "mechanics")
public class Mechanic extends BaseEntity {

    @Column(name = "employee_code")
    private String employeeCode;

    @Column(name = "name")
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "status")
    private String status;

    /**
     * Mechanic role / job title (SRS 4.6).
     * e.g. "Junior Mechanic", "Senior Mechanic", "Auto Electrician".
     * Nullable — added in migration V5; existing rows keep NULL until updated.
     */
    @Column(name = "role", length = 60)
    private String role;

    /**
     * Date the mechanic joined the garage (SRS 4.6).
     * Nullable — added in migration V5.
     */
    @Column(name = "joining_date")
    private LocalDate joiningDate;

    /**
     * Monthly salary in the garage's local currency (SRS 4.6).
     * DECIMAL(12,2) per SRS monetary-value guideline — never floating-point.
     * Nullable — added in migration V5.
     * Read access is restricted to OWNER/MANAGER at the service layer.
     */
    @Column(name = "salary", precision = 12, scale = 2)
    private BigDecimal salary;

    /**
     * AES-GCM encrypted Government / Aadhaar ID (SRS 4.6).
     * Stores Base64(IV || ciphertext) produced by GovernmentIdEncryptionService.
     * Plaintext is NEVER stored here.  NULL until voluntarily submitted.
     * Added in migration V6.
     */
    @Column(name = "government_id_encrypted", columnDefinition = "TEXT")
    private String governmentIdEncrypted;

    /**
     * Skills for this mechanic (SRS 4.6).
     * CascadeType.ALL + orphanRemoval: saving the mechanic with a new list
     * replaces the old skills atomically.
     */
    @OneToMany(mappedBy = "mechanic",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<MechanicSkill> skills = new ArrayList<>();
}
