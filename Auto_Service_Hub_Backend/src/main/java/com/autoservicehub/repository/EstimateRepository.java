package com.autoservicehub.repository;

import com.autoservicehub.entity.Estimate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for Estimate. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface EstimateRepository extends JpaRepository<Estimate, Long>, JpaSpecificationExecutor<Estimate> {

    /** Estimates raised against one job card, newest first. */
    List<Estimate> findByJobCardIdOrderByCreatedAtDescIdDesc(Long jobCardId);

    /** Paged variant of the above. */
    Page<Estimate> findByJobCardIdOrderByCreatedAtDescIdDesc(Long jobCardId, Pageable pageable);
}
