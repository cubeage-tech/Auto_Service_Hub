package com.autoservicehub.repository;

import com.autoservicehub.entity.Purchase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for Purchase. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long>, JpaSpecificationExecutor<Purchase> {

    /** Orders placed with one supplier, newest first. */
    Page<Purchase> findBySupplierIdOrderByIdDesc(Long supplierId, Pageable pageable);

    /**
     * Does this supplier have any purchase history?
     *
     * <p>Used by the supplier delete guard: a supplier that has been ordered
     * from is part of the purchase record and cannot be removed.
     */
    boolean existsBySupplierId(Long supplierId);

    /**
     * How many purchase orders a supplier has, for the supplier response.
     *
     * <p>Lives here rather than on {@code SupplierRepository} because it counts
     * purchases, and {@code Purchase} is the entity being counted.
     */
    @Query("SELECT COUNT(p) FROM Purchase p WHERE p.supplier.id = :supplierId")
    long countPurchasesBySupplierId(@Param("supplierId") Long supplierId);
}
