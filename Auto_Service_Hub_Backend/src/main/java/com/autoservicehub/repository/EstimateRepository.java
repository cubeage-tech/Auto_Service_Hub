package com.autoservicehub.repository;

import com.autoservicehub.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for Estimate. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface EstimateRepository extends JpaRepository<Estimate, Long>, JpaSpecificationExecutor<Estimate> {
	    @Query("select e from Estimate e left join e.jobCard card left join card.appointment appointment " +
		    "left join appointment.assignedAdvisor advisor " +
		    "where card is null or appointment is null or advisor is null or advisor.id = :advisorId")
	Page<Estimate> findVisibleToAdvisor(@Param("advisorId") Long advisorId, Pageable pageable);
}
