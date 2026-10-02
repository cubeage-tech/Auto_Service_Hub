package com.autoservicehub.repository;

import com.autoservicehub.entity.Invoice;
import com.autoservicehub.projection.DailyRevenueProjection;
import com.autoservicehub.projection.InvoiceStatusTotalProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Invoice.
 * Extends JpaSpecificationExecutor for dynamic filters (SRS 9, 17).
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /**
     * The invoice already produced from an estimate, if any.
     *
     * <p>The readable second line of defence behind the {@code UNIQUE} constraint
     * on {@code invoices.estimate_id}: it produces a clear "already converted"
     * message, where the constraint alone would surface only as an opaque
     * constraint violation.
     */
    Optional<Invoice> findByEstimateId(Long estimateId);

    // ── Existing dashboard / report queries ───────────────────────────────
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i WHERE i.invoiceDate = CURRENT_DATE")
    BigDecimal sumTodayRevenue();

    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i WHERE i.invoiceDate BETWEEN :from AND :to")
    BigDecimal sumTotalByInvoiceDateBetween(LocalDate from, LocalDate to);

    long countByInvoiceDateBetween(LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.status = :status AND jc.assignedDate BETWEEN :from AND :to " +
           "AND (:mechanicId IS NULL OR jc.mechanic.id = :mechanicId)")
    BigDecimal sumTotalByMechanicAndJobStatus(@Param("mechanicId") Long mechanicId,
                                              @Param("status")     String status,
                                              @Param("from")       LocalDateTime from,
                                              @Param("to")         LocalDateTime to);

    // ── FR-CRM-7: All invoices for a customer via jobCard FK chain ────────
    @Query("SELECT i FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.customer.id = :customerId ORDER BY i.invoiceDate DESC")
    List<Invoice> findByCustomerId(@Param("customerId") Long customerId);

    // ── FR-CRM-7: Total spend for a customer ──────────────────────────────
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.customer.id = :customerId")
    BigDecimal sumTotalByCustomerId(@Param("customerId") Long customerId);

    // ── FR-CRM-7: Open (unpaid) invoice count for a customer ─────────────
    @Query("SELECT COUNT(i) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.customer.id = :customerId AND i.status <> 'PAID'")
    long countOpenByCustomerId(@Param("customerId") Long customerId);

    // ── FR-CRM-7: Open invoice total amount for a customer ───────────────
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.customer.id = :customerId AND i.status <> 'PAID'")
    BigDecimal sumOpenTotalByCustomerId(@Param("customerId") Long customerId);

    // ── Invoices raised against one job card, newest first ───────────────
    List<Invoice> findByJobCardIdOrderByInvoiceDateDescIdDesc(Long jobCardId);

    /** Paged variant of the above. */
    Page<Invoice> findByJobCardIdOrderByInvoiceDateDescIdDesc(Long jobCardId, Pageable pageable);

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-4: Revenue and profit reporting
    //
    // Windows are half-open, [from, to) on invoice_date, which is a plain date
    // column — so these use BETWEEN, matching the existing queries above.
    //
    // The invoice total is the revenue figure of record. InvoiceItem rows are
    // NOT used to attribute revenue to parts: InvoiceItem has no link to Part,
    // only a free-text description, so there is no way to say which parts a given
    // invoice line covered. That is a hard limit of the schema, not an oversight
    // in these queries.
    // ══════════════════════════════════════════════════════════════════════

    /** Invoice count and value per status, for the revenue breakdown. */
    @Query("SELECT i.status AS status, COUNT(i) AS invoiceCount, COALESCE(SUM(i.total), 0) AS total " +
           "FROM Invoice i " +
           "WHERE i.invoiceDate >= :from AND i.invoiceDate <= :to " +
           "GROUP BY i.status ORDER BY i.status ASC")
    List<InvoiceStatusTotalProjection> countAndTotalGroupedByStatus(
            @Param("from") LocalDate from,
            @Param("to")   LocalDate to);

    /**
     * Invoiced value and count per day — the revenue trend for export.
     *
     * <p>Grouping on invoice_date is a genuine per-day bucket, so no truncation
     * is needed here; the column is already a date.
     */
    @Query("SELECT i.invoiceDate AS invoiceDate, COALESCE(SUM(i.total), 0) AS total, " +
           "       COUNT(i) AS invoiceCount " +
           "FROM Invoice i " +
           "WHERE i.invoiceDate >= :from AND i.invoiceDate <= :to " +
           "GROUP BY i.invoiceDate ORDER BY i.invoiceDate ASC")
    List<DailyRevenueProjection> sumGroupedByInvoiceDate(@Param("from") LocalDate from,
                                                          @Param("to")   LocalDate to);

    /** Invoiced value for jobs of one status, with the report filters applied. */
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE i.invoiceDate >= :from AND i.invoiceDate <= :to " +
           "AND (:jobStatus IS NULL OR UPPER(jc.status) = UPPER(:jobStatus)) " +
           "AND (:mechanicId IS NULL OR jc.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR jc.vehicle.id  = :vehicleId)")
    BigDecimal sumTotalByDateRangeWithJobFilters(@Param("from")        LocalDate from,
                                                 @Param("to")          LocalDate to,
                                                 @Param("jobStatus")   String      jobStatus,
                                                 @Param("mechanicId")  Long        mechanicId,
                                                 @Param("vehicleId")   Long        vehicleId);

    /** Invoice count for the same filter combination, for the average. */
    @Query("SELECT COUNT(i) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE i.invoiceDate >= :from AND i.invoiceDate <= :to " +
           "AND (:jobStatus IS NULL OR UPPER(jc.status) = UPPER(:jobStatus)) " +
           "AND (:mechanicId IS NULL OR jc.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR jc.vehicle.id  = :vehicleId)")
    long countByDateRangeWithJobFilters(@Param("from")        LocalDate from,
                                        @Param("to")          LocalDate to,
                                        @Param("jobStatus")   String      jobStatus,
                                        @Param("mechanicId")  Long        mechanicId,
                                        @Param("vehicleId")   Long        vehicleId);

    /** Invoices raised for jobs of one status, in the window — for export. */
    @Query("SELECT i FROM Invoice i JOIN i.jobCard jc " +
           "WHERE i.invoiceDate >= :from AND i.invoiceDate <= :to " +
           "AND (:jobStatus IS NULL OR UPPER(jc.status) = UPPER(:jobStatus)) " +
           "AND (:mechanicId IS NULL OR jc.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR jc.vehicle.id  = :vehicleId) " +
           "ORDER BY i.invoiceDate ASC, i.id ASC")
    List<Invoice> findByDateRangeWithJobFilters(@Param("from")        LocalDate from,
                                                @Param("to")          LocalDate to,
                                                @Param("jobStatus")   String      jobStatus,
                                                @Param("mechanicId")  Long        mechanicId,
                                                @Param("vehicleId")   Long        vehicleId);

    /** Total invoiced on one day — used by the daily workshop report. */
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
           "WHERE jc.assignedDate >= :from AND jc.assignedDate < :to")
    BigDecimal sumTotalByJobAssignedDateBetween(@Param("from") LocalDateTime from,
                                                @Param("to")   LocalDateTime to);
}
