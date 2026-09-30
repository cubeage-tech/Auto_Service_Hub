package com.autoservicehub.repository;

import com.autoservicehub.entity.InspectionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for InspectionItem. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface InspectionItemRepository extends JpaRepository<InspectionItem, Long>, JpaSpecificationExecutor<InspectionItem> {

    /**
     * Checklist findings for one inspection, in the order they were recorded.
     * Ordered by id (not createdAt) so that a batch of items saved in the same
     * transaction keeps its submitted order — several rows legitimately share
     * the same {@code createdAt} timestamp.
     */
    List<InspectionItem> findByInspectionIdOrderByIdAsc(Long inspectionId);
}
