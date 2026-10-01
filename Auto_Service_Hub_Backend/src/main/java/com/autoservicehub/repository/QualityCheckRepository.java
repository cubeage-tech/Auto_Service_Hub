package com.autoservicehub.repository;

import com.autoservicehub.entity.QualityCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for QualityCheck. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 *
 * <p>All reads are ordered by {@code attemptNo} descending so the newest attempt is
 * always first; this is what makes "latest QC governs" unambiguous.
 */
@Repository
public interface QualityCheckRepository
        extends JpaRepository<QualityCheck, Long>, JpaSpecificationExecutor<QualityCheck> {

    /** Full QC history for a job card, newest attempt first. */
    List<QualityCheck> findByJobCardIdOrderByAttemptNoDesc(Long jobCardId);

    /** The governing QC result: the highest attempt number for this job card. */
    Optional<QualityCheck> findFirstByJobCardIdOrderByAttemptNoDesc(Long jobCardId);

    /** Highest attempt number recorded so far, used to allocate the next one. */
    @Query("select coalesce(max(qc.attemptNo), 0) from QualityCheck qc where qc.jobCard.id = :jobCardId")
    int findMaxAttemptNo(@Param("jobCardId") Long jobCardId);
}
