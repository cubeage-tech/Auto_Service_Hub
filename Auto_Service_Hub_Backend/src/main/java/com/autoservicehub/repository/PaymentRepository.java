package com.autoservicehub.repository;

import com.autoservicehub.entity.Payment;
import com.autoservicehub.projection.DailyPaymentProjection;
import com.autoservicehub.projection.PaymentModeTotalProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for Payment. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    /** Payments recorded against one invoice, oldest first. */
    List<Payment> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    /** Paged variant of the above, keeping the same deterministic order. */
    Page<Payment> findByInvoiceIdOrderByIdAsc(Long invoiceId, Pageable pageable);

    /**
     * Sum of an invoice's payments with the given status, used to derive the
     * outstanding amount. Coalesces to zero so callers get a usable BigDecimal
     * when the invoice has never been paid.
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
         + "WHERE p.invoice.id = :invoiceId AND UPPER(p.status) = :status")
    BigDecimal sumAmountByInvoiceIdAndStatus(@Param("invoiceId") Long invoiceId,
                                             @Param("status") String status);

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-4: Payment / revenue reporting
    //
    // Windows are half-open, [from, to) on paid_at — when the money arrived, not
    // when the invoice was raised. Collections and invoiced revenue are
    // deliberately measured on different clocks; they will not agree for the
    // same window when a payment crosses a period boundary, and that is correct
    // rather than a defect.
    //
    // Every query filters by status as a PARAMETER rather than hard-coding
    // 'SUCCESS', so the caller states which statuses count as collected instead
    // of the rule being buried in a literal.
    // ══════════════════════════════════════════════════════════════════════

    /** Total collected by payments of the given status in the window. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE UPPER(p.status) = UPPER(:status) " +
           "AND p.paidAt >= :from AND p.paidAt < :to")
    BigDecimal sumAmountByStatusAndPaidAtBetween(@Param("status") String         status,
                                                 @Param("from")  LocalDateTime from,
                                                 @Param("to")    LocalDateTime to);

    /** How many payments of the given status landed in the window. */
    long countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
            String status, LocalDateTime from, LocalDateTime to);

    /** Payments of the given status in the window, oldest first. */
    List<Payment> findByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByIdAsc(
            String status, LocalDateTime from, LocalDateTime to);

    /**
     * Payments grouped by payment timestamp.
     *
     * <p>Grouped on the raw timestamp for the same portability reason as the
     * customer-growth series: JPQL cannot truncate a date portably, so the
     * reporting layer buckets these into days.
     */
    @Query("SELECT p.paidAt AS paidAt, SUM(p.amount) AS total, COUNT(p) AS paymentCount " +
           "FROM Payment p " +
           "WHERE UPPER(p.status) = UPPER(:status) " +
           "AND p.paidAt >= :from AND p.paidAt < :to " +
           "GROUP BY p.paidAt ORDER BY p.paidAt ASC")
    List<DailyPaymentProjection> sumGroupedByPaidAt(@Param("status") String         status,
                                                    @Param("from")  LocalDateTime from,
                                                    @Param("to")    LocalDateTime to);

    /** Payments grouped by mode (CASH / CARD / UPI …) for the window. */
    @Query("SELECT p.mode AS mode, COUNT(p) AS paymentCount, COALESCE(SUM(p.amount), 0) AS total " +
           "FROM Payment p " +
           "WHERE UPPER(p.status) = UPPER(:status) " +
           "AND p.paidAt >= :from AND p.paidAt < :to " +
           "GROUP BY p.mode ORDER BY SUM(p.amount) DESC")
    List<PaymentModeTotalProjection> sumGroupedByMode(@Param("status") String         status,
                                                       @Param("from")  LocalDateTime from,
                                                       @Param("to")    LocalDateTime to);
}
