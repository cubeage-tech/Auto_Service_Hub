package com.autoservicehub.repository;

import com.autoservicehub.entity.Part;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Part. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PartRepository extends JpaRepository<Part, Long>, JpaSpecificationExecutor<Part> {

    /**
     * Number of parts at or below their own reorder level.
     *
     * <p>Compares each part's {@code stockQty} against its own {@code minStock},
     * which is what "low stock" means. The previous
     * {@code countByStockQtyLessThanEqualAndMinStockGreaterThan(0, 0)} form
     * compared both columns against hard-coded zeros and so only ever counted
     * parts with zero stock, ignoring every part's configured minimum.
     */
    @Query("SELECT COUNT(p) FROM Part p WHERE p.stockQty IS NOT NULL "
         + "AND p.minStock IS NOT NULL AND p.stockQty <= p.minStock")
    long countLowStock();

    /**
     * The low-stock parts themselves, for the inventory view.
     * Ordered by how far below its minimum each part has fallen.
     */
    @Query("SELECT p FROM Part p WHERE p.stockQty IS NOT NULL "
         + "AND p.minStock IS NOT NULL AND p.stockQty <= p.minStock "
         + "ORDER BY (p.minStock - p.stockQty) DESC, p.sku ASC")
    List<Part> findLowStock();

    /**
     * Paged variant of {@link #findLowStock()}.
     */
    @Query(value = "SELECT p FROM Part p WHERE p.stockQty IS NOT NULL "
                + "AND p.minStock IS NOT NULL AND p.stockQty <= p.minStock "
                + "ORDER BY (p.minStock - p.stockQty) DESC, p.sku ASC",
           countQuery = "SELECT COUNT(p) FROM Part p WHERE p.stockQty IS NOT NULL "
                      + "AND p.minStock IS NOT NULL AND p.stockQty <= p.minStock")
    Page<Part> findLowStock(Pageable pageable);

    Optional<Part> findBySkuIgnoreCase(String sku);
}
