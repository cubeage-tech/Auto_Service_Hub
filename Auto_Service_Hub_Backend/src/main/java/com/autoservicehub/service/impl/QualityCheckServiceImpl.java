package com.autoservicehub.service.impl;

import com.autoservicehub.dto.QualityCheckRequestDTO;
import com.autoservicehub.dto.QualityCheckResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.QualityCheck;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.QualityCheckRepository;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.QualityCheckService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Quality-check recording and history (SRS 4.5 FR-JOB-6, SRS 12 BR-02).
 *
 * <p><strong>Authorization.</strong> No new role is introduced. The existing role
 * model ({@code AppConstants}) has no quality-checker role, so QC is restricted to
 * the roles that already hold job-card supervisory authority — the same set
 * {@link MechanicAccessService#isManagementUser()} recognises: ADMIN, OWNER,
 * MANAGER and SERVICE_ADVISOR. A plain MECHANIC cannot record a check.
 *
 * <p><strong>Separation of duties.</strong> On top of the role restriction, the
 * authenticated user is refused if they are one of the mechanics assigned to that
 * job card, so the person who performed the repair cannot sign off their own work.
 *
 * <p><strong>Provenance.</strong> The checker is resolved from the security
 * context via {@link MechanicAccessService#currentUser()}; the request DTO carries
 * no user id, so a client cannot attribute a check to someone else.
 *
 * <p><strong>History.</strong> Every attempt is appended with a server-allocated
 * {@code attemptNo}; nothing is overwritten, so a later FAIL is never masked by an
 * earlier PASS.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class QualityCheckServiceImpl implements QualityCheckService {

    private final QualityCheckRepository       qualityCheckRepository;
    private final JobCardRepository            jobCardRepository;
    private final MechanicAccessService        accessService;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public QualityCheckResponseDTO record(Long jobCardId, QualityCheckRequestDTO request) {
        // Pessimistic write lock on the job card, held for the rest of this
        // transaction. Serialises concurrent QC submissions for the same job card so
        // the max(attemptNo) read below cannot be stale. Same transaction as the
        // insert, because the class is @Transactional.
        JobCard jobCard = jobCardRepository.findByIdForUpdate(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));

        // Record-level scoping, same as every other job-card sub-resource.
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);

        // Resolved once, from the security context — never from the request body.
        User checker = accessService.currentUser();
        assertCanPerformQualityCheck(jobCard, checker);

        QualityCheck qc = new QualityCheck();
        qc.setJobCard(jobCard);
        qc.setResult(normalizeResult(request.getResult()));
        qc.setRemarks(request.getRemarks() == null ? null : request.getRemarks().trim());
        qc.setCheckedBy(checker);
        qc.setCheckedAt(LocalDateTime.now());
        // Safe now: no other transaction can be inside this job-card row's critical
        // section, so this read cannot be stale. The unique constraint on
        // (job_card_id, attempt_no) remains as a last-resort integrity backstop.
        qc.setAttemptNo(qualityCheckRepository.findMaxAttemptNo(jobCardId) + 1);

        return toResponse(qualityCheckRepository.save(qc));
    }

    @Override
    @Transactional(readOnly = true)
    public List<QualityCheckResponseDTO> listForJobCard(Long jobCardId) {
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);
        return qualityCheckRepository.findByJobCardIdOrderByAttemptNoDesc(jobCardId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QualityCheckResponseDTO latest(Long jobCardId) {
        // Same visibility rules as the other reads: resolved here, not by the caller,
        // so this method can never be used as an unscoped back door.
        JobCard jobCard = findJobCard(jobCardId);
        advisorAccessService.assertCanAccess(jobCard);
        accessService.assertCanAccess(jobCard);

        return qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(jobCardId)
                .map(this::toResponse)
                .orElse(null);
    }
    // ── Authorization helpers ──────────────────────────────────────────────

    /**
     * Only supervisory roles may record a check, and never on a job they repaired.
     */
    private void assertCanPerformQualityCheck(JobCard jobCard, User currentUser) {
        if (!accessService.isManagementUser()) {
            throw new AccessDeniedException(
                    "Only ADMIN, OWNER, MANAGER or SERVICE_ADVISOR may record a quality check.");
        }

        Mechanic currentMechanic = currentUser.getMechanic();
        if (currentMechanic != null && accessService.isAssignedTo(jobCard, currentMechanic)) {
            // A user who also carries a mechanic profile must not sign off a job
            // they are assigned to repair.
            throw new AccessDeniedException(
                    "A mechanic assigned to this job card cannot record its quality check.");
        }
    }

    private String normalizeResult(String raw) {
        String result = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!QualityCheck.RESULT_PASS.equals(result) && !QualityCheck.RESULT_FAIL.equals(result)) {
            throw new BusinessRuleException("Quality check result must be PASS or FAIL.");
        }
        return result;
    }

    private JobCard findJobCard(Long id) {
        return jobCardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + id));
    }

    private QualityCheckResponseDTO toResponse(QualityCheck qc) {
        QualityCheckResponseDTO dto = new QualityCheckResponseDTO();
        dto.setId(qc.getId());
        dto.setJobCardId(qc.getJobCard() == null ? null : qc.getJobCard().getId());
        dto.setResult(qc.getResult());
        dto.setRemarks(qc.getRemarks());
        dto.setAttemptNo(qc.getAttemptNo());
        dto.setCheckedByUserId(qc.getCheckedBy() == null ? null : qc.getCheckedBy().getId());
        dto.setCheckedByName(qc.getCheckedBy() == null ? null : qc.getCheckedBy().getFullName());
        dto.setCheckedAt(qc.getCheckedAt());
        dto.setCreatedAt(qc.getCreatedAt());
        return dto;
    }
}
