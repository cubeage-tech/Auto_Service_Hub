package com.autoservicehub.service.impl;

import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.QualityCheck;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.QualityCheckRepository;
import com.autoservicehub.service.DeliveryGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Delivery-gate evaluation for SRS 12 BR-02.
 *
 * <p>The rule implemented here is:
 * <ol>
 *   <li>every task on the job card is COMPLETED, and</li>
 *   <li>the governing (highest {@code attemptNo}) quality check is PASS.</li>
 * </ol>
 *
 * <p><strong>Latest-result semantics.</strong> Only the newest attempt is consulted.
 * An older PASS therefore cannot be used to satisfy the gate once a later FAIL has
 * been recorded, which is why {@link QualityCheckRepository#findFirstByJobCardIdOrderByAttemptNoDesc}
 * is used rather than an "exists a PASS" query.
 *
 * <p><strong>Where it is enforced.</strong> Invoked by
 * {@code JobCardServiceImpl.changeStatus} immediately before {@code DELIVERED} is
 * written, once the transition itself has been validated. That method is the only
 * place the job-card status is set from an API request, so both the mechanic and
 * the management update paths are covered by this single call.
 *
 * <p><strong>Concurrency.</strong> This is a read-only pre-flight evaluation. It
 * takes no lock and writes nothing, so it cannot deadlock against the pessimistic
 * job-card lock taken when recording a quality check. It does not, however,
 * serialise delivery against quality-check recording: it reads whatever is visible
 * at query time, and a quality check committed concurrently afterwards is not
 * guaranteed to be seen. Closing that window is a separate, deliberate design
 * decision rather than a property of this class.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryGateServiceImpl implements DeliveryGateService {

    private final JobTaskRepository      jobTaskRepository;
    private final QualityCheckRepository qualityCheckRepository;

    @Override
    public List<String> blockingReasons(JobCard jobCard) {
        List<String> reasons = new ArrayList<>();

        // COMPLETED and the legacy DONE both count as finished (see the repository
        // query), so legacy rows written before the status vocabulary was tightened
        // cannot block delivery.
        long incomplete = jobTaskRepository.countIncompleteByJobCardId(jobCard.getId());
        if (incomplete > 0) {
            // Explicitly ordered by the query, so this message is deterministic.
            List<String> statuses = jobTaskRepository.findDistinctStatusesByJobCardId(jobCard.getId());
            reasons.add("All repair tasks must be COMPLETED before delivery; "
                    + incomplete + " task(s) are still outstanding (statuses: "
                    + (statuses.isEmpty() ? "unset" : String.join(", ", statuses)) + ").");
        }

        QualityCheck latest =
                qualityCheckRepository.findFirstByJobCardIdOrderByAttemptNoDesc(jobCard.getId()).orElse(null);

        if (latest == null) {
            reasons.add("A passing quality check must be recorded before delivery; "
                    + "no quality check exists for this job card.");
        } else if (!latest.isPass()) {
            reasons.add("The most recent quality check (attempt " + latest.getAttemptNo() + ") is FAIL; "
                    + "complete the repairs and record a new quality check.");
        }

        return reasons;
    }

    @Override
    public boolean isDeliverable(JobCard jobCard) {
        return blockingReasons(jobCard).isEmpty();
    }

    @Override
    public void assertCanDeliver(JobCard jobCard) {
        List<String> reasons = blockingReasons(jobCard);
        if (!reasons.isEmpty()) {
            // BusinessRuleException maps to HTTP 409 BUSINESS_RULE_VIOLATION.
            throw new BusinessRuleException(
                    "Job card " + jobCard.getJobCardNumber() + " cannot be delivered: " + String.join(" ", reasons));
        }
    }
}
