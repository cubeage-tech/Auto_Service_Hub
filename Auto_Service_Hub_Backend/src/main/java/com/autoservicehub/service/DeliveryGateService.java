package com.autoservicehub.service;

import com.autoservicehub.entity.JobCard;

import java.util.List;

/**
 * Evaluates whether a job card satisfies the pre-delivery conditions of SRS 12
 * BR-02 ("a vehicle cannot be delivered while mandatory repair/QC information is
 * incomplete").
 *
 * <p><strong>Where it is enforced.</strong> {@code JobCardServiceImpl.changeStatus}
 * calls {@link #assertCanDeliver(JobCard)} immediately before writing
 * {@code DELIVERED}, after the job-card transition itself has been validated.
 * {@code changeStatus} is the single point through which both the mechanic and the
 * management update paths reach the status field, so the gate covers both.
 *
 * <p><strong>Scope of the check.</strong> It performs a read-only evaluation against
 * the current committed state of the job card's tasks and quality checks. It takes no
 * lock and mutates nothing, so it introduces no lock-ordering interaction. It is a
 * pre-flight check evaluated inside the delivery transaction, not a serialisation
 * mechanism: a quality check or task completion committed concurrently after the read
 * is not guaranteed to be seen by the evaluation in progress. See the concurrency
 * design note in the Phase 3 report.
 *
 * <p>It contains no job-card mutation and no status-change logic, so it does not
 * overlap with the billing work.
 */
public interface DeliveryGateService {

    /** Reasons the gate currently blocks delivery, in report order. Empty means deliverable. */
    List<String> blockingReasons(JobCard jobCard);

    /** True when the job card may transition to DELIVERED. */
    boolean isDeliverable(JobCard jobCard);

    /**
     * Throws {@code BusinessRuleException} (HTTP 409) when the job card is not
     * deliverable. Intended to be called from the status-transition path.
     */
    void assertCanDeliver(JobCard jobCard);
}
