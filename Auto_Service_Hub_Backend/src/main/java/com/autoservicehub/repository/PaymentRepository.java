package com.autoservicehub.repository;

import com.autoservicehub.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Spring Data JPA repository for Payment. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {
	    @Query("select p from Payment p left join p.invoice invoice left join invoice.jobCard card " +
		    "left join card.appointment appointment left join appointment.assignedAdvisor advisor " +
		    "where invoice is null or card is null or appointment is null or advisor is null or advisor.id = :advisorId")
	Page<Payment> findVisibleToAdvisor(@Param("advisorId") Long advisorId, Pageable pageable);

	@Query("select coalesce(sum(p.amount), 0) from Payment p " +
			"where p.paidAt is not null and p.paidAt >= :from and p.paidAt < :to")
	BigDecimal sumRecordedPaymentsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	@Query("select coalesce(sum(p.amount), 0) from Payment p join p.invoice invoice " +
			"join invoice.jobCard card join card.appointment appointment " +
			"where appointment.assignedAdvisor.id = :advisorId and p.paidAt is not null " +
			"and p.paidAt >= :from and p.paidAt < :to")
	BigDecimal sumRecordedPaymentsForAdvisorBetween(@Param("advisorId") Long advisorId,
													@Param("from") LocalDateTime from,
													@Param("to") LocalDateTime to);
}
