package com.autoservicehub.repository;

import com.autoservicehub.entity.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for Part. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PartRepository extends JpaRepository<Part, Long>, JpaSpecificationExecutor<Part> {
    long countByStockQtyLessThanEqualAndMinStockGreaterThan(int stockQty, int minStock);

    @Query("select count(p) from Part p where p.minStock is not null and p.minStock > 0 " +
            "and (p.stockQty is null or p.stockQty <= p.minStock)")
    long countLowStockParts();
}
