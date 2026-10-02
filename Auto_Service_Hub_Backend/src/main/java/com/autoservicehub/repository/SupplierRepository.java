package com.autoservicehub.repository;

import com.autoservicehub.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for Supplier. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    /**
     * Duplicate-supplier guard (FR-INV-2).
     *
     * <p>Compared case-insensitively and ignoring surrounding whitespace, so
     * "Bharat Auto" and " bharat auto " are recognised as the same supplier
     * rather than being stored as two. {@code supplierId} is excluded so a
     * supplier can be updated without colliding with its own row.
     *
     * <p>Returns a count rather than a boolean so the caller can tell "no match"
     * from "matched" — either way the duplicate is rejected, but a count keeps
     * the check a single query with no entity load.
     */
    @Query("SELECT COUNT(s) FROM Supplier s WHERE LOWER(TRIM(s.name)) = LOWER(TRIM(:name)) "
         + "AND s.id <> :supplierId")
    long countByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("supplierId") Long supplierId);

    /**
     * Paged supplier search by free text (FR-INV-2).
     *
     * <p>Matches case-insensitively against name, phone, email or address, so a
     * user can find a supplier by whichever of those they happen to know —
     * usually the name, but frequently only the phone number of the yard that
     * supplies them.
     *
     * <p>{@code CONCAT('%', :q, '%')} with an explicit LIKE rather than a derived
     * query, because one derived method cannot search four columns.
     */
    @Query("SELECT s FROM Supplier s WHERE "
         + "LOWER(s.name)    LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(s.phone)   LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(s.email)   LIKE LOWER(CONCAT('%', :q, '%')) OR "
         + "LOWER(s.address) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<Supplier> search(@Param("q") String q, Pageable pageable);
}
