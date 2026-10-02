package com.autoservicehub.service.impl;

import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskResponseDTO;
import com.autoservicehub.dto.JobTaskStatusRequestDTO;
import com.autoservicehub.dto.JobTaskWorkNotesRequestDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.JobTaskService;
import com.autoservicehub.util.BillingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Repair tasks and labour (FR-JOB-3, FR-JOB-5).
 *
 * <p>Two rules define this service.
 *
 * <p>The first is that a task is never an orphan. The job card is resolved and
 * attached here, on every path that writes, so there is no code path that
 * produces a task belonging to nothing — and the entity declares the column NOT
 * NULL behind that, so the database would refuse one even if a future caller
 * tried. The parent is taken from the path id, never from the payload, so a
 * client cannot write a task onto a job it is not working on.
 *
 * <p>The second is that labour is money, so it is normalised the same way
 * billing money is: through {@link BillingCalculator}, at money scale, never
 * negative. A job's total is always the sum of its stored tasks, computed in the
 * database, so no client figure is ever trusted.
 *
 * <p>Status is a closed set validated here rather than a free string. A task in
 * an unrecognised state is invisible to every "what is outstanding" question the
 * workshop asks, which is worse than refusing it at the point of entry.
 *
 * <p>On top of that sit two workflow rules. A {@link JobCardRepository#STATUS_DELIVERED
 * DELIVERED} job is closed: the vehicle has left, so its tasks are a historical
 * record rather than live work, and nothing may change them. And a task may only
 * move forwards through its statuses — see {@link #assertTransitionAllowed}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobTaskServiceImpl implements JobTaskService {

    /** Raised, not started. */
    public static final String STATUS_PENDING     = "PENDING";
    /** Being worked on. */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** Work done. */
    public static final String STATUS_COMPLETED   = "COMPLETED";
    /** Dropped — not going to be done, and its labour must not be charged. */
    public static final String STATUS_CANCELLED   = "CANCELLED";

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "JOB_TASK";

    private final JobTaskRepository repository;
    private final JobCardRepository jobCardRepository;
    private final MechanicRepository mechanicRepository;
    private final AuditService auditService;
    private final BillingCalculator  calculator;

    @Override
    public JobTaskResponseDTO create(Long jobCardId, JobTaskRequestDTO request) {
        JobCard jobCard = jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));
        assertJobCardNotDelivered(jobCard);

        requireDescription(request.getDescription());
        String status = resolveStatus(request.getStatus(), STATUS_PENDING);
        BigDecimal labourCost = resolveLabourCost(request.getLabourCost());
        Mechanic mechanic = resolveMechanic(request.getMechanicId());

        JobTask entity = new JobTask();
        entity.setJobCard(jobCard);
        entity.setDescription(request.getDescription().trim());
        entity.setStatus(status);
        entity.setLabourCost(labourCost);
        entity.setMechanic(mechanic);
        entity.setWorkNotes(trimToNull(request.getWorkNotes()));

        JobTaskResponseDTO response = toResponse(repository.save(entity));
        auditService.recordSuccess(AUDIT_ENTITY, response.getId(), AuditAction.JOB_TASK_CREATE,
                "Task created on job card " + jobCardId
                        + "; status=" + status
                        + ", labourCost=" + labourCost
                        + ", mechanicId=" + request.getMechanicId());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public JobTaskResponseDTO getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JobTaskResponseDTO> listByJobCard(Long jobCardId, Pageable pageable) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return repository.findByJobCardIdOrderByIdAsc(jobCardId, pageable).map(this::toResponse);
    }

    @Override
    public JobTaskResponseDTO update(Long id, JobTaskRequestDTO request) {
        JobTask existing = findOrThrow(id);
        assertTaskJobCardNotDelivered(existing);

        requireDescription(request.getDescription());
        existing.setDescription(request.getDescription().trim());
        existing.setLabourCost(resolveLabourCost(request.getLabourCost()));
        existing.setWorkNotes(trimToNull(request.getWorkNotes()));

        // A null status means "leave it alone", so a caller updating the notes
        // of an in-progress task cannot accidentally reset it to pending.
        if (request.getStatus() != null) {
            String nextStatus = resolveStatus(request.getStatus(), existing.getStatus());
            assertTransitionAllowed(existing.getStatus(), nextStatus);
            existing.setStatus(nextStatus);
        }
        // Same for the mechanic: only reassigned when one is named, since the
        // dedicated assignMechanic endpoint is how a task is cleared.
        if (request.getMechanicId() != null) {
            existing.setMechanic(resolveMechanic(request.getMechanicId()));
        }

        // The parent job card is deliberately not touched here.
        JobTaskResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.JOB_TASK_UPDATE,
                "Task updated: description=" + response.getDescription()
                        + ", labourCost=" + response.getLabourCost()
                        + ", status=" + response.getStatus());
        return response;
    }

    @Override
    public void delete(Long id) {
        JobTask existing = findOrThrow(id);
        assertTaskJobCardNotDelivered(existing);
        // Captured before the delete: afterwards the row is gone and the trail
        // would lose the description that identifies what was removed.
        String description = existing.getDescription();
        repository.delete(existing);
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.JOB_TASK_DELETE,
                "Task deleted: " + description);
    }

    @Override
    public JobTaskResponseDTO updateStatus(Long id, JobTaskStatusRequestDTO request) {
        JobTask existing = findOrThrow(id);
        assertTaskJobCardNotDelivered(existing);
        String from = existing.getStatus();
        String to = resolveStatus(request.getStatus(), existing.getStatus());
        assertTransitionAllowed(from, to);
        existing.setStatus(to);
        JobTaskResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.JOB_TASK_STATUS_CHANGE,
                "Status " + from + " -> " + response.getStatus());
        return response;
    }

    @Override
    public JobTaskResponseDTO updateWorkNotes(Long id, JobTaskWorkNotesRequestDTO request) {
        JobTask existing = findOrThrow(id);
        // Notes describe work done on the vehicle; once it has left, that account
        // is closed and no further note belongs to it.
        assertTaskJobCardNotDelivered(existing);
        existing.setWorkNotes(trimToNull(request.getWorkNotes()));
        JobTaskResponseDTO response = toResponse(repository.save(existing));
        // The note text itself is not recorded: it is free-form and long, and the
        // task still carries the authoritative copy. Recording a count is enough
        // to show the work was documented.
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.JOB_TASK_UPDATE_WORK_NOTES,
                "Work notes updated on task " + id);
        return response;
    }

    @Override
    public JobTaskResponseDTO assignMechanic(Long id, Long mechanicId) {
        JobTask existing = findOrThrow(id);
        assertTaskJobCardNotDelivered(existing);
        Long from = existing.getMechanic() == null ? null : existing.getMechanic().getId();
        // A null id un-assigns: resolveMechanic(null) returns null.
        existing.setMechanic(resolveMechanic(mechanicId));
        JobTaskResponseDTO response = toResponse(repository.save(existing));
        auditService.recordSuccess(AUDIT_ENTITY, id, AuditAction.JOB_TASK_ASSIGN_MECHANIC,
                "Mechanic " + from + " -> " + mechanicId);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal totalLabourCost(Long jobCardId) {
        if (!jobCardRepository.existsById(jobCardId)) {
            throw new ResourceNotFoundException("JobCard not found: " + jobCardId);
        }
        return calculator.money(repository.sumLabourCostByJobCardId(jobCardId));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * A delivered job card is closed to further task work.
     *
     * <p>{@code DELIVERED} is the terminal state of the job-card workflow
     * ({@code RECEIVED → INSPECTION → IN_REPAIR → QUALITY_CHECK → DELIVERED}),
     * and the constant is reused from {@link JobCardRepository} so the two
     * modules cannot disagree about what "delivered" means.
     *
     * <p>Only that one state is blocked, deliberately. Every earlier state is
     * still live work — a job awaiting inspection genuinely needs its tasks
     * recorded against it — so blocking anything else would stop legitimate
     * work rather than protect a closed record.
     *
     * <p>Reads are not affected: an operator still needs to see what was done on
     * a delivered job, which is most of the point of keeping it.
     */
    private void assertJobCardNotDelivered(JobCard jobCard) {
        if (jobCard == null || !JobCardRepository.STATUS_DELIVERED.equalsIgnoreCase(jobCard.getStatus())) {
            return;
        }
        throw new BusinessRuleException(
                "JobCard " + jobCard.getId() + " is DELIVERED and its tasks can no longer be modified.");
    }

    /** As {@link #assertJobCardNotDelivered(JobCard)}, for a task already loaded. */
    private void assertTaskJobCardNotDelivered(JobTask task) {
        assertJobCardNotDelivered(task.getJobCard());
    }

    /**
     * A task may only move forwards through the workflow.
     *
     * <p>{@code PENDING → IN_PROGRESS | CANCELLED} and
     * {@code IN_PROGRESS → COMPLETED | CANCELLED}. {@code COMPLETED} and
     * {@code CANCELLED} are terminal: work that is done or dropped is a
     * historical fact, and letting it move back would silently reopen it while
     * its recorded labour stayed in the job total.
     *
     * <p>Staying put is always allowed, so an idempotent re-submission — the same
     * status sent twice, or a status echoed back by a client — is not an error.
     *
     * <p>A null current status is treated as {@code PENDING}: that is what the
     * column means when it has never been set, and refusing transitions from it
     * would strand a task that could never be started.
     */
    private void assertTransitionAllowed(String from, String to) {
        String current = (from == null || from.isBlank()) ? STATUS_PENDING : from.trim().toUpperCase();
        String target = to == null ? null : to.trim().toUpperCase();

        if (target == null || target.equals(current)) {
            return;
        }

        boolean allowed = switch (current) {
            case STATUS_PENDING     -> STATUS_IN_PROGRESS.equals(target) || STATUS_CANCELLED.equals(target);
            case STATUS_IN_PROGRESS -> STATUS_COMPLETED.equals(target)   || STATUS_CANCELLED.equals(target);
            // Terminal states: only staying put is legal.
            case STATUS_COMPLETED, STATUS_CANCELLED -> false;
            // An unrecognised stored status is not this rule's problem to police;
            // resolveStatus already rejects setting one, so allow the move.
            default -> true;
        };

        if (!allowed) {
            throw new BusinessRuleException(
                    "Cannot change task status from " + current + " to " + target
                            + ". Allowed next states from " + current + ": "
                            + allowedTargets(current) + ".");
        }
    }

    /** The statuses reachable from {@code current}, for the error message. */
    private String allowedTargets(String current) {
        return switch (current) {
            case STATUS_PENDING     -> STATUS_IN_PROGRESS + " or " + STATUS_CANCELLED;
            case STATUS_IN_PROGRESS -> STATUS_COMPLETED + " or " + STATUS_CANCELLED;
            case STATUS_COMPLETED, STATUS_CANCELLED -> "none, the state is final";
            default                 -> "any supported status";
        };
    }

    private JobTask findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobTask not found: " + id));
    }

    /** A task nobody can describe is not actionable work. */
    private void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new BusinessRuleException("description is required.");
        }
    }

    /**
     * Validates a status against the closed set and returns it normalised.
     *
     * <p>{@code fallback} is what a null status resolves to: the default on
     * create, and the current value on update — where null means "unchanged".
     */
    private String resolveStatus(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        String normalized = requested.trim().toUpperCase();
        if (!STATUS_PENDING.equals(normalized)
                && !STATUS_IN_PROGRESS.equals(normalized)
                && !STATUS_COMPLETED.equals(normalized)
                && !STATUS_CANCELLED.equals(normalized)) {
            throw new BusinessRuleException(
                    "Unsupported task status: '" + requested.trim() + "'. Allowed values are "
                    + STATUS_PENDING + ", " + STATUS_IN_PROGRESS + ", "
                    + STATUS_COMPLETED + ", " + STATUS_CANCELLED + ".");
        }
        return normalized;
    }

    /**
     * Normalises a labour cost to money scale and refuses a negative one.
     *
     * <p>Null is zero: a task may be raised before its cost is known, and
     * treating "not yet costed" as zero keeps the job total meaningful. The
     * scale check comes from {@link BillingCalculator} so task labour cannot
     * carry sub-paise precision that billing money may not.
     */
    private BigDecimal resolveLabourCost(BigDecimal requested) {
        if (requested == null) {
            return calculator.money(BigDecimal.ZERO);
        }
        if (requested.signum() < 0) {
            throw new BusinessRuleException(
                    "labourCost must not be negative, got: " + requested.toPlainString());
        }
        if (requested.scale() > BillingCalculator.MONEY_SCALE
                && requested.stripTrailingZeros().scale() > BillingCalculator.MONEY_SCALE) {
            throw new BusinessRuleException(
                    "labourCost must not have more than " + BillingCalculator.MONEY_SCALE
                    + " decimal places, got: " + requested.toPlainString());
        }
        return calculator.money(requested);
    }

    /** Resolves an optional mechanic; a null id means un-assigned. */
    private Mechanic resolveMechanic(Long mechanicId) {
        if (mechanicId == null) {
            return null;
        }
        return mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
    }

    /** Blank optional text is stored as null rather than as an empty string. */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private JobTaskResponseDTO toResponse(JobTask t) {
        JobTaskResponseDTO dto = new JobTaskResponseDTO();
        dto.setId(t.getId());
        dto.setJobCardId(t.getJobCard().getId());
        dto.setJobCardNumber(t.getJobCard().getJobCardNumber());
        if (t.getMechanic() != null) {
            dto.setMechanicId(t.getMechanic().getId());
            dto.setMechanicName(t.getMechanic().getName());
        }
        dto.setDescription(t.getDescription());
        dto.setStatus(t.getStatus());
        dto.setLabourCost(t.getLabourCost());
        dto.setWorkNotes(t.getWorkNotes());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }
}