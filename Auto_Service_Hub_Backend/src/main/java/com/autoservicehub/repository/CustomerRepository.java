package com.autoservicehub.repository;

import com.autoservicehub.entity.Customer;
import com.autoservicehub.projection.DateCountProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for Customer.
 * Extends JpaSpecificationExecutor for dynamic filters (SRS 9, 17).
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    // ── Existing report query ─────────────────────────────────────────────
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    // ── FR-CRM-1: Check for duplicate phone before create/update ─────────
    boolean existsByPhoneAndIdNot(String phone, Long id);
    boolean existsByPhone(String phone);

    // ── FR-CRM-6: Server-side search across name, phone, email ───────────
    @Query("SELECT c FROM Customer c WHERE " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:q IS NULL OR :q = '' OR " +
           "  LOWER(c.name)  LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "  c.phone        LIKE CONCAT('%', :q, '%') OR " +
           "  LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Customer> search(@Param("q") String q,
                          @Param("status") String status,
                          Pageable pageable);

    // ── FR-CRM-6: Search by vehicle registration (joins vehicles table) ───
    @Query("SELECT DISTINCT c FROM Customer c JOIN Vehicle v ON v.customer.id = c.id " +
           "WHERE LOWER(v.registrationNo) LIKE LOWER(CONCAT('%', :regNo, '%'))")
    Page<Customer> searchByVehicleRegistration(@Param("regNo") String regNo, Pageable pageable);

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-5: Customer growth
    //
    // Windows are half-open, [from, to), on created_at. New-customer counts use
    // derived queries (the column and type make them trivial); the time-series
    // grouping needs @Query because it aggregates.
    // ══════════════════════════════════════════════════════════════════════

    /** Customers created inside the window — the new-customer count. */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from, LocalDateTime to);

    /**
     * Customers on record at the end of the window.
     *
     * <p>Created before the window ENDS, not before it starts — the question is
     * how large the base had become by the close of the period, which keeps this
     * comparable across windows of different lengths.
     */
    long countByCreatedAtLessThan(LocalDateTime toExclusive);

    /**
     * New customers over time, grouped by registration timestamp.
     *
     * <p>Grouped on the raw timestamp because JPQL has no portable
     * date-truncation function; the reporting layer folds these into days or
     * months. Doing the grouping here rather than in Java means a wide window
     * does not load every customer row.
     */
    @Query("SELECT c.createdAt AS createdAt, COUNT(c) AS rowCount FROM Customer c " +
           "WHERE c.createdAt >= :from AND c.createdAt < :to " +
           "GROUP BY c.createdAt ORDER BY c.createdAt ASC")
    List<DateCountProjection> countNewCustomersGroupedByCreatedAt(
            @Param("from") LocalDateTime from,
            @Param("to")   LocalDateTime to);
}
