package com.autoservicehub.repository;

import com.autoservicehub.entity.Followup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data JPA repository for Followup. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface FollowupRepository extends JpaRepository<Followup, Long>, JpaSpecificationExecutor<Followup> {

    /** Follow-ups in an open state (not COMPLETED, not CANCELLED). */
    List<Followup> findByStatusInOrderByDueDateAscIdAsc(List<String> statuses);

    /** Open follow-ups that have come due on or before {@code asOf}, oldest first. */
    List<Followup> findByStatusInAndDueDateLessThanEqualOrderByIdAsc(
            List<String> statuses, LocalDate asOf);

    /**
     * Open follow-ups due on or before {@code asOf} that have not been
     * notified yet. {@code notifiedAt IS NULL} is what keeps the scheduled
     * processing idempotent.
     */
    List<Followup> findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
            List<String> statuses, LocalDate asOf);

    List<Followup> findByCustomerIdOrderByDueDateDesc(Long customerId);
}
