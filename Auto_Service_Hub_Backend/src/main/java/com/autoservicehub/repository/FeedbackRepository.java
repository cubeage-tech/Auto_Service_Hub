package com.autoservicehub.repository;

import com.autoservicehub.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Feedback (SRS FR-CRM-5).
 */
@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long>, JpaSpecificationExecutor<Feedback> {

    /** Paged feedback for a customer, newest first. */
    Page<Feedback> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    /** Full list for Customer 360 (no pagination needed for typical feedback counts). */
    List<Feedback> findTop10ByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Feedback for a specific job card. */
    Optional<Feedback> findByJobCardId(Long jobCardId);

    /** Average rating for a customer (null if no feedback). */
    @Query("SELECT AVG(f.rating) FROM Feedback f WHERE f.customer.id = :customerId")
    Double findAverageRatingByCustomerId(@Param("customerId") Long customerId);

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-2: Mechanic performance — customer satisfaction
    //
    // Feedback is the ONLY quality signal in the schema: there is no rating
    // column on Mechanic or JobCard. These queries therefore attribute a rating
    // to a mechanic via the job card the feedback was left against, which is
    // the only path that link exists on.
    //
    // AVG returns null when there is no feedback, and null is deliberately not
    // coerced to zero here: "nobody rated this mechanic" and "everybody rated
    // this mechanic zero" are different facts, and collapsing them would show an
    // unrated mechanic as the worst performer in the workshop.
    // ══════════════════════════════════════════════════════════════════════

    /** Average customer rating for one mechanic's completed work (null if none). */
    @Query("SELECT AVG(f.rating) FROM Feedback f JOIN f.jobCard jc " +
           "WHERE jc.mechanic.id = :mechanicId")
    Double findAverageRatingByMechanicId(@Param("mechanicId") Long mechanicId);

    /** Average rating for a mechanic's work in a date window (null if none). */
    @Query("SELECT AVG(f.rating) FROM Feedback f JOIN f.jobCard jc " +
           "WHERE jc.mechanic.id = :mechanicId " +
           "AND jc.assignedDate >= :from AND jc.assignedDate < :to")
    Double findAverageRatingByMechanicIdAndDateRange(@Param("mechanicId") Long         mechanicId,
                                                     @Param("from")      LocalDateTime from,
                                                     @Param("to")        LocalDateTime to);

    /** How many ratings a mechanic has on record — the denominator for the average. */
    long countByJobCardMechanicId(Long mechanicId);
}
