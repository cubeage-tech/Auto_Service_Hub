package com.autoservicehub.repository;

import com.autoservicehub.entity.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for PurchaseItem. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long>, JpaSpecificationExecutor<PurchaseItem> {

    /**
     * Ordered lines of one purchase, in recorded order.
     *
     * <p>Ordered by id rather than createdAt because a batch of items saved in
     * the same transaction shares the same createdAt timestamp — the same
     * reasoning as {@code EstimateItemRepository}.
     */
    List<PurchaseItem> findByPurchaseIdOrderByIdAsc(Long purchaseId);
}
