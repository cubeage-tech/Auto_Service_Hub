package com.autoservicehub.repository;

import com.autoservicehub.entity.EstimateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for EstimateItem. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface EstimateItemRepository extends JpaRepository<EstimateItem, Long>, JpaSpecificationExecutor<EstimateItem> {

    /**
     * Quoted lines of one estimate, in recorded order. Ordered by id rather than
     * createdAt because a batch of items saved in the same transaction shares
     * the same createdAt timestamp.
     */
    List<EstimateItem> findByEstimateIdOrderByIdAsc(Long estimateId);
}
