package com.autoservicehub.service.impl;

import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FollowupRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.FollowupService;
import com.autoservicehub.util.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer Follow-up & Retention (SRS 4.10).
 *
 * <p>Rules enforced here:
 * <ul>
 *   <li>the customer must exist, and a job card, when given, must exist and
 *       must belong to that same customer — a follow-up cannot be raised
 *       against a job for somebody else;</li>
 *   <li>status is restricted to the follow-up workflow's own states;</li>
 *   <li>a newly created follow-up may not be dated in the past, since a
 *       reminder that was already missed is a data-entry error (an existing
 *       follow-up naturally becomes overdue as time passes, which is fine);</li>
 *   <li>re-opening a follow-up that was already notified clears the stamp, so a
 *       genuinely new reminder can be raised for it again.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FollowupServiceImpl implements FollowupService {

    /** Follow-up workflow states. */
    public static final String STATUS_PENDING     = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED   = "COMPLETED";
    public static final String STATUS_CANCELLED   = "CANCELLED";

    /** States in which a follow-up still needs doing. */
    public static final List<String> OPEN_STATUSES = List.of(STATUS_PENDING, STATUS_IN_PROGRESS);

    private static final List<String> ALL_STATUSES =
            List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_CANCELLED);

    /**
     * Roles that handle customer contact, and so are notified when a follow-up
     * falls due. Taken from the project's existing role constants.
     */
    private static final List<String> NOTIFY_ROLES = List.of(
            AppConstants.ROLE_SERVICE_ADVISOR,
            AppConstants.ROLE_MANAGER,
            AppConstants.ROLE_OWNER,
            AppConstants.ROLE_ADMIN);

    private final FollowupRepository        repository;
    private final CustomerRepository       customerRepository;
    private final JobCardRepository        jobCardRepository;
    private final UserRepository           userRepository;
    private final NotificationServiceImpl  notificationService;

    @Override
    public FollowupResponseDTO create(FollowupRequestDTO request) {
        Followup entity = new Followup();
        mapToEntity(request, entity, true);
        return toResponse(repository.save(entity));
    }

    @Override
    public FollowupResponseDTO update(Long id, FollowupRequestDTO request) {
        Followup existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Followup not found: " + id));
        mapToEntity(request, existing, false);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public FollowupResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Followup not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowupResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Followup not found: " + id);
        }
        repository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowupResponseDTO> listPending(Pageable pageable) {
        List<Followup> open = repository.findByStatusInOrderByDueDateAscIdAsc(OPEN_STATUSES);
        return new PageImpl<>(open.stream().map(this::toResponse).toList(), pageable, open.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowupResponseDTO> listDue(LocalDate asOf, Pageable pageable) {
        List<Followup> due = repository.findByStatusInAndDueDateLessThanEqualOrderByIdAsc(
                OPEN_STATUSES, asOf);
        return new PageImpl<>(due.stream().map(this::toResponse).toList(), pageable, due.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowupResponseDTO> listByCustomer(Long customerId, Pageable pageable) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        List<Followup> all = repository.findByCustomerIdOrderByDueDateDesc(customerId);
        return new PageImpl<>(all.stream().map(this::toResponse).toList(), pageable, all.size());
    }

    // ── Due-follow-up → notification ──────────────────────────────────────

    /**
     * Notifies the customer-facing staff about every open follow-up that is due
     * and has not been notified yet.
     *
     * <p>Idempotent on two levels: only un-notified follow-ups are selected
     * ({@code notifiedAt IS NULL}), and each notification write is additionally
     * guarded by the user/reference pair, so a follow-up cannot produce two
     * notifications for the same recipient even if the stamp were lost.
     *
     * <p>The follow-up is stamped only after its notifications are written, so
     * a failure part-way through rolls the whole batch back and the follow-up
     * is picked up again on the next run rather than being silently skipped.
     *
     * <p>Notification text carries the follow-up and job-card ids only — no
     * customer name or phone number is copied in.
     */
    @Override
    @Transactional
    public int notifyDueFollowUps(LocalDate asOf) {
        List<Followup> due = repository
                .findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                        OPEN_STATUSES, asOf);

        int created = 0;
        for (Followup followup : due) {
            for (User user : userRepository.findActiveByRoleNameIn(NOTIFY_ROLES)) {
                boolean made = notificationService.notifyUserOnce(
                        user.getId(),
                        "Follow-up due: " + shortReason(followup.getReason()),
                        buildMessage(followup, asOf),
                        NotificationServiceImpl.REFERENCE_FOLLOWUP,
                        followup.getId());
                if (made) {
                    created++;
                }
            }
            followup.setNotifiedAt(LocalDateTime.now());
        }
        if (!due.isEmpty()) {
            repository.saveAll(due);
        }
        return created;
    }

    private String buildMessage(Followup followup, LocalDate asOf) {
        StringBuilder sb = new StringBuilder();
        sb.append("Follow-up ").append(followup.getId());
        if (followup.getJobCard() != null) {
            sb.append(" for job card ").append(followup.getJobCard().getId());
        }
        if (followup.getCustomer() != null) {
            sb.append(" (customer ").append(followup.getCustomer().getId()).append(")");
        }
        sb.append(" is due on ").append(followup.getDueDate());
        if (followup.getDueDate() != null && followup.getDueDate().isBefore(asOf)) {
            sb.append(" and is overdue");
        }
        sb.append(". Reason: ").append(followup.getReason());
        return sb.toString();
    }

    private String shortReason(String reason) {
        if (reason == null) {
            return "(no reason given)";
        }
        return reason.length() <= 60 ? reason : reason.substring(0, 57) + "...";
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Resolves and checks the references, the status and the due date.
     *
     * @param isNew true on create, which additionally refuses a due date that
     *              has already passed
     */
    private void mapToEntity(FollowupRequestDTO r, Followup e, boolean isNew) {
        Customer customer = customerRepository.findById(r.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found: " + r.getCustomerId()));
        e.setCustomer(customer);

        if (r.getJobCardId() != null) {
            JobCard jobCard = jobCardRepository.findById(r.getJobCardId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "JobCard not found: " + r.getJobCardId()));
            // A follow-up must relate to one of this customer's own visits.
            if (jobCard.getCustomer() != null
                    && !jobCard.getCustomer().getId().equals(customer.getId())) {
                throw new BusinessRuleException(
                        "JobCard " + r.getJobCardId() + " belongs to a different customer and cannot "
                        + "be linked to a follow-up for customer " + customer.getId() + ".");
            }
            e.setJobCard(jobCard);
        } else {
            e.setJobCard(null);
        }

        e.setDueDate(r.getDueDate());
        e.setReason(r.getReason());

        if (isNew && r.getDueDate() != null && r.getDueDate().isBefore(LocalDate.now())) {
            throw new BusinessRuleException(
                    "dueDate " + r.getDueDate() + " is in the past; a new follow-up must be "
                    + "due today or later.");
        }

        String previousStatus = e.getStatus();
        String status = resolveStatus(r.getStatus(), previousStatus);
        e.setStatus(status);

        // Re-opening a follow-up that had been closed clears the stamp, so a
        // genuinely new reminder can be raised for it on the next run. This
        // applies ONLY to a CLOSED → OPEN transition: every other combination
        // keeps the stamp, so editing a follow-up that is still open cannot
        // re-arm it and let the scheduler notify the same customer twice.
        boolean reopened = isClosedStatus(previousStatus) && !isClosedStatus(status);
        if (reopened && e.getNotifiedAt() != null) {
            e.setNotifiedAt(null);
        }
    }

    private String resolveStatus(String requested, String current) {
        if (requested == null || requested.isBlank()) {
            return current != null ? current : STATUS_PENDING;
        }
        String normalized = requested.trim().toUpperCase();
        if (!ALL_STATUSES.contains(normalized)) {
            throw new BusinessRuleException(
                    "Unsupported follow-up status: '" + requested + "'. Allowed values are "
                    + ALL_STATUSES + ".");
        }
        return normalized;
    }

    private boolean isClosed(Followup e) {
        return isClosedStatus(e.getStatus());
    }

    /**
     * Whether a status means the follow-up is finished — COMPLETED or CANCELLED.
     * A null status is not closed, since a follow-up that has never been given a
     * status still needs doing.
     */
    private boolean isClosedStatus(String status) {
        return STATUS_COMPLETED.equalsIgnoreCase(status)
                || STATUS_CANCELLED.equalsIgnoreCase(status);
    }

    private FollowupResponseDTO toResponse(Followup e) {
        FollowupResponseDTO dto = new FollowupResponseDTO();
        dto.setId(e.getId());
        if (e.getCustomer() != null) {
            dto.setCustomerId(e.getCustomer().getId());
            dto.setCustomerName(e.getCustomer().getName());
            dto.setCustomerPhone(e.getCustomer().getPhone());
        }
        if (e.getJobCard() != null) {
            dto.setJobCardId(e.getJobCard().getId());
            dto.setJobCardNumber(e.getJobCard().getJobCardNumber());
        }
        dto.setDueDate(e.getDueDate());
        dto.setReason(e.getReason());
        dto.setStatus(e.getStatus());
        dto.setNotifiedAt(e.getNotifiedAt());
        dto.setClosed(isClosed(e));
        dto.setDue(!isClosed(e)
                && e.getDueDate() != null
                && !e.getDueDate().isAfter(LocalDate.now()));
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
