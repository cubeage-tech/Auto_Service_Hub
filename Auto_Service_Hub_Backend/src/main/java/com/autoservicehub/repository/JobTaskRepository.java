package com.autoservicehub.repository;

import com.autoservicehub.entity.JobTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for JobTask. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface JobTaskRepository extends JpaRepository<JobTask, Long>, JpaSpecificationExecutor<JobTask> {

    /**
     * One job card's tasks, in recorded order.
     *
     * <p>Ordered by id rather than createdAt because a batch of tasks saved in
     * the same transaction shares the same createdAt timestamp — the same
     * reasoning as {@code EstimateItemRepository}.
     */
    List<JobTask> findByJobCardIdOrderByIdAsc(Long jobCardId);

    /** Paged variant of {@link #findByJobCardIdOrderByIdAsc(Long)}. */
    Page<JobTask> findByJobCardIdOrderByIdAsc(Long jobCardId, Pageable pageable);

    /**
     * A job card's total labour cost, summed in the database.
     *
     * <p>{@code COALESCE(..., 0)} rather than a null sum, so a job with no
     * tasks totals zero instead of null. {@code labour_cost} is null-safe in
     * the sum but a task written before labour cost was captured contributes
     * nothing, which is the correct reading of "no cost recorded".
     */
    @Query("SELECT COALESCE(SUM(t.labourCost), 0) FROM JobTask t WHERE t.jobCard.id = :jobCardId")
    BigDecimal sumLabourCostByJobCardId(@Param("jobCardId") Long jobCardId);

    /** Tasks on one job card — used to remove them with their job card. */
    long countByJobCardId(Long jobCardId);
}
