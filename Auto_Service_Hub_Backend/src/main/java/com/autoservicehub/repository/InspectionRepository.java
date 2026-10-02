package com.autoservicehub.repository;

import com.autoservicehub.entity.Inspection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Inspection. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface InspectionRepository extends JpaRepository<Inspection, Long>, JpaSpecificationExecutor<Inspection> {

    // ── Inspection → Job Card integration ───────────────────────────────────

    /**
     * Chronological inspection history for a vehicle. Used by the
     * inspection list endpoint and to resolve the inspection that produced a
     * given job card.
     */
    Page<Inspection> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId, Pageable pageable);

    /**
     * The inspection that produced a given job card, if any. A job card is
     * raised from at most one inspection, hence {@code Optional}.
     */
    Optional<Inspection> findByJobCardId(Long jobCardId);
}
