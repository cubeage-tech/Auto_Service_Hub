package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'job_tasks' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One line of repair work on a {@link JobCard} (FR-JOB-3): what has to be
 * done, who is doing it, whether it is done, and what the labour is worth.
 *
 * <p>{@code jobCard} is mandatory and NOT NULL. A task with no job card cannot
 * be reached from any job, cannot be billed and cannot be attributed to a
 * mechanic, so an orphan task is not a valid state — the column constraint is
 * what actually enforces that, rather than relying on every caller to remember.
 *
 * <p>{@code mechanic} is optional. A job card already carries the lead
 * mechanic for the whole job; a task may additionally name the individual doing
 * that piece of work, and may leave it unset when the task is not yet allocated.
 *
 * <p>{@code labourCost} is a cost the workshop incurs, decided server-side from
 * the request and validated as non-negative; the job's total labour is the sum
 * of its tasks' costs, never a client-supplied figure.
 */
@Getter
@Setter
@Entity
@Table(name = "job_tasks", indexes = {
        @Index(name = "idx_job_task_job_card_id", columnList = "job_card_id"),
        @Index(name = "idx_job_task_mechanic_id", columnList = "mechanic_id")
})
public class JobTask extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_card_id", nullable = false)
    private JobCard jobCard;

    /** Optional — the technician doing this task, when allocated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id")
    private Mechanic mechanic;

    @Column(name = "description")
    private String description;

    /** PENDING | IN_PROGRESS | COMPLETED | CANCELLED — see {@code JobTaskServiceImpl}. */
    @Column(name = "status")
    private String status;

    /** Labour cost incurred for this task. Must not be negative. */
    @Column(name = "labour_cost")
    private BigDecimal labourCost;

    /**
     * Notes recorded while working the task (FR-JOB-5). Free text, nullable: a
     * task can legitimately have no notes, but never a blank one where the
     * mechanic meant to write something.
     */
    @Column(name = "work_notes", columnDefinition = "TEXT")
    private String workNotes;
}
