
package com.autoservicehub.repository;

import com.autoservicehub.entity.Invoice;
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

@Repository
public interface InvoiceRepository
        extends JpaRepository<Invoice, Long>,
                JpaSpecificationExecutor<Invoice> {

    // Invoice status count
    long countByStatusIgnoreCase(String status);

    // Pending invoices for a service advisor
    @Query("select count(i) from Invoice i join i.jobCard j " +
            "where j.appointment.assignedAdvisor.id = :advisorId " +
            "and upper(i.status) = 'PENDING'")
    long countPendingForAdvisor(@Param("advisorId") Long advisorId);

    // Pending invoices for an advisor within a date range
    @Query("select count(i) from Invoice i join i.jobCard j " +
            "where j.appointment.assignedAdvisor.id = :advisorId " +
            "and upper(i.status) = 'PENDING' " +
            "and i.invoiceDate >= :from and i.invoiceDate < :to")
    long countPendingForAdvisorBetween(
            @Param("advisorId") Long advisorId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // Invoices visible to a service advisor
    @Query("select i from Invoice i left join i.jobCard card " +
            "left join card.appointment appointment " +
            "left join appointment.assignedAdvisor advisor " +
            "where card is null or appointment is null " +
            "or advisor is null or advisor.id = :advisorId")
    Page<Invoice> findVisibleToAdvisor(
            @Param("advisorId") Long advisorId,
            Pageable pageable);

    // Today's revenue
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i " +
            "WHERE i.invoiceDate = CURRENT_DATE")
    BigDecimal sumTodayRevenue();

    // Revenue between invoice dates
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i " +
            "WHERE i.invoiceDate BETWEEN :from AND :to")
    BigDecimal sumTotalByInvoiceDateBetween(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    long countByInvoiceDateBetween(LocalDate from, LocalDate to);

    // Revenue for a mechanic and job status, using the job card assigned date
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
            "WHERE jc.status = :status " +
            "AND jc.assignedDate BETWEEN :from AND :to " +
            "AND (:mechanicId IS NULL OR jc.mechanic.id = :mechanicId)")
    BigDecimal sumTotalByMechanicAndJobStatus(
            @Param("mechanicId") Long mechanicId,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // All invoices for a customer, newest first
    @Query("SELECT i FROM Invoice i JOIN i.jobCard jc " +
            "WHERE jc.customer.id = :customerId ORDER BY i.invoiceDate DESC")
    List<Invoice> findByCustomerId(@Param("customerId") Long customerId);

    // Total spend for a customer
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
            "WHERE jc.customer.id = :customerId")
    BigDecimal sumTotalByCustomerId(@Param("customerId") Long customerId);

    // Open invoice count for a customer
    @Query("SELECT COUNT(i) FROM Invoice i JOIN i.jobCard jc " +
            "WHERE jc.customer.id = :customerId AND i.status <> 'PAID'")
    long countOpenByCustomerId(@Param("customerId") Long customerId);

    // Open invoice total for a customer
    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i JOIN i.jobCard jc " +
            "WHERE jc.customer.id = :customerId AND i.status <> 'PAID'")
    BigDecimal sumOpenTotalByCustomerId(@Param("customerId") Long customerId);
}