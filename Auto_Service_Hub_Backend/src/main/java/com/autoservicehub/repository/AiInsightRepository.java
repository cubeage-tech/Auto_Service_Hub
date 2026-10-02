package com.autoservicehub.repository;

import com.autoservicehub.entity.AiInsight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for AiInsight. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface AiInsightRepository extends JpaRepository<AiInsight, Long>, JpaSpecificationExecutor<AiInsight> {

    // ── AI Insights report (FR-REP-9) ──────────────────────────────────────────

    /**
     * Insights created inside a window, newest first.
     *
     * <p>The window is half-open, {@code [from, to)}, matching every other report
     * query in this project: the caller passes the start of {@code to}'s day as
     * {@code to}, so the whole of that day is included without depending on
     * sub-second precision.
     *
     * <p>Ordered newest first because this is a review feed — a report on
     * recently generated AI output is read from the top down. The order is by
     * {@code createdAt} rather than id so it reflects when the insight was
     * actually generated, not its insertion order within a batch.
     */
    @Query("SELECT i FROM AiInsight i WHERE i.createdAt >= :from AND i.createdAt < :to "
         + "ORDER BY i.createdAt DESC, i.id DESC")
    List<AiInsight> findCreatedInPeriod(@Param("from") LocalDateTime from,
                                        @Param("to")   LocalDateTime to);
}
