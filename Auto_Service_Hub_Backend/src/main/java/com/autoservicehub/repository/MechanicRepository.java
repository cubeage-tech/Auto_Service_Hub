package com.autoservicehub.repository;

import com.autoservicehub.entity.Mechanic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for Mechanic. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface MechanicRepository extends JpaRepository<Mechanic, Long>, JpaSpecificationExecutor<Mechanic> {

    /**
     * FR-AI-17: Mechanics in a given employment status, used to build the
     * candidate pool for AI Mechanic Assignment. Derived query — no schema change.
     */
    List<Mechanic> findByStatusIgnoreCase(String status);
}
