package com.autoservicehub.repository;

import com.autoservicehub.entity.JobTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

/**
 * Spring Data JPA repository for JobTask. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface JobTaskRepository extends JpaRepository<JobTask, Long>, JpaSpecificationExecutor<JobTask> {
	List<JobTask> findByMechanicIdOrderByCreatedAtAsc(Long mechanicId);
	List<JobTask> findByJobCardIdOrderByCreatedAtAsc(Long jobCardId);

	@Query("select count(t) from JobTask t where t.mechanic.id = :mechanicId " +
			"and (t.status is null or upper(t.status) not in ('COMPLETED', 'DONE'))")
	long countPendingByMechanicId(@Param("mechanicId") Long mechanicId);

	// ── Phase 3 / SRS BR-02: delivery-gate support ───────────────────────────
	// Used by DeliveryGateServiceImpl. Additive only; existing queries unchanged.

	/**
	 * Number of tasks on a job card that are not finished.
	 *
	 * <p><strong>Terminal statuses.</strong> Both {@code COMPLETED} and the legacy
	 * {@code DONE} count as finished, exactly as the pre-existing
	 * {@link #countPendingByMechanicId} treats them. A null status counts as
	 * incomplete, matching that query's intent. This keeps the delivery gate from
	 * regressing on legacy rows written before the status vocabulary was constrained
	 * to PENDING / IN_PROGRESS / COMPLETED in JobTaskServiceImpl.
	 */
	@Query("select count(t) from JobTask t where t.jobCard.id = :jobCardId " +
			"and (t.status is null or upper(t.status) not in ('COMPLETED', 'DONE'))")
	long countIncompleteByJobCardId(@Param("jobCardId") Long jobCardId);

	/**
	 * Distinct statuses of the tasks on a job card, for precise error messages.
	 *
	 * <p>Explicitly ordered by status so the rendered message is deterministic; the
	 * delivery gate embeds this list in its 409 response body.
	 */
	@Query("select distinct t.status from JobTask t where t.jobCard.id = :jobCardId " +
			"and t.status is not null order by t.status")
	List<String> findDistinctStatusesByJobCardId(@Param("jobCardId") Long jobCardId);
}
