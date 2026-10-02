package com.autoservicehub.service;

import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.dto.JobTaskStatusRequestDTO;
import com.autoservicehub.dto.JobTaskWorkNotesRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repair tasks and labour (FR-JOB-3, FR-JOB-5) — the lines of work that make up
 * a job card.
 *
 * <p>A task always belongs to exactly one job card. Every mutating method
 * therefore takes the job card id explicitly, so a task can never be written
 * against a job the caller is not working on, and a task cannot be created or
 * left behind without a parent.
 *
 * <p>Labour is stored per task and a job's total is the server-side sum of its
 * tasks. No method accepts a total from the client.
 */
public interface JobTaskService {

    /**
     * Records a new task against a job card.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown job card or mechanic
     * @throws com.autoservicehub.exception.BusinessRuleException  blank description,
     *         negative labour cost, or an unsupported status
     */
    JobTaskResponseDTO create(Long jobCardId, JobTaskRequestDTO request);

    /** @throws com.autoservicehub.exception.ResourceNotFoundException unknown task */
    JobTaskResponseDTO getById(Long id);

    /** One job card's tasks, in recorded order. */
    Page<JobTaskResponseDTO> listByJobCard(Long jobCardId, Pageable pageable);

    /**
     * Updates a task's own fields.
     *
     * <p>The parent job card is NOT reassignable: a task belongs to the job it
     * was raised on, and moving it between jobs would corrupt both jobs'
     * labour totals. Omit {@code status} to leave the status unchanged.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown task or mechanic
     * @throws com.autoservicehub.exception.BusinessRuleException  blank description,
     *         negative labour cost, or an unsupported status
     */
    JobTaskResponseDTO update(Long id, JobTaskRequestDTO request);

    /**
     * Removes a task.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown task
     */
    void delete(Long id);

    /**
     * Moves a task to a new status (FR-JOB-5).
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown task
     * @throws com.autoservicehub.exception.BusinessRuleException  unsupported status
     */
    JobTaskResponseDTO updateStatus(Long id, JobTaskStatusRequestDTO request);

    /**
     * Records notes on a task (FR-JOB-5), leaving every other field untouched.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown task
     */
    JobTaskResponseDTO updateWorkNotes(Long id, JobTaskWorkNotesRequestDTO request);

    /**
     * Allocates or de-allocates the mechanic doing this task.
     *
     * <p>A null {@code mechanicId} un-assigns. Separate from {@link #update} so
     * assigning someone to a task cannot restate its cost.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown task or mechanic
     */
    JobTaskResponseDTO assignMechanic(Long id, Long mechanicId);

    /**
     * A job card's total labour cost, summed server-side from its tasks.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException unknown job card
     */
    java.math.BigDecimal totalLabourCost(Long jobCardId);
}