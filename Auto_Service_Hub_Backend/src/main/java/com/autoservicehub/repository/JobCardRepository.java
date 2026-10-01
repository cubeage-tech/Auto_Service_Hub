package com.autoservicehub.repository;

import com.autoservicehub.entity.JobCard;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for JobCard.
 * Extends JpaSpecificationExecutor for dynamic filters (SRS 9, 17).
 */
@Repository
public interface JobCardRepository extends JpaRepository<JobCard, Long>, JpaSpecificationExecutor<JobCard> {

    // ── Phase 3 / SRS BR-02: QC attempt-number sequencing ────────────────────
    /**
     * Pessimistic write lock on the job-card row, so concurrent quality-check
     * submissions for the same job card serialise and cannot compute the same
     * attempt number. Mirrors the existing
     * {@code MechanicRepository.findByIdForUpdate} convention.
     *
     * <p>Callers must invoke this inside an active transaction (the lock is held
     * until that transaction commits or rolls back), and must acquire it before any
     * other job-card write in the same transaction to keep the lock order stable.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select jc from JobCard jc where jc.id = :id")
    Optional<JobCard> findByIdForUpdate(@Param("id") Long id);

    // ── Existing dashboard/report queries ──────────────────────────────────
    long countByStatus(String status);
    long countByAssignedDateBetween(LocalDateTime from, LocalDateTime to);
    long countByStatusAndAssignedDateBetween(String status, LocalDateTime from, LocalDateTime to);

    

    // ── FR-AI-17: Current open workload for a mechanic ─────────────────────
    // Counts the job cards a mechanic currently holds, i.e. those in any status
    // other than the terminal DELIVERED state. This is the only workload signal
    // the current data model supports — there is no capacity or shift data.
    long countByMechanicIdAndStatusNot(Long mechanicId, String excludedStatus);

    // ── FR-CRM-3: Chronological service history per customer ──────────────
    List<JobCard> findByCustomerIdOrderByAssignedDateDesc(Long customerId);

    // ── FR-CRM-3: Chronological service history per vehicle ───────────────
    List<JobCard> findByVehicleIdOrderByAssignedDateDesc(Long vehicleId);

    // ── FR-CRM-7: Most recent completed job for a customer (last-service date)
    @Query("SELECT j FROM JobCard j WHERE j.customer.id = :customerId " +
           "AND j.status = 'DELIVERED' ORDER BY j.completedDate DESC")
    List<JobCard> findLastDeliveredByCustomerId(@Param("customerId") Long customerId);

    // ── FR-CRM-6: Last service date subquery support ──────────────────────
    @Query("SELECT MAX(j.completedDate) FROM JobCard j " +
           "WHERE j.customer.id = :customerId AND j.status = 'DELIVERED'")
    Optional<LocalDateTime> findLastServiceDateByCustomerId(@Param("customerId") Long customerId);

    long countByMechanicIdAndStatusAndAssignedDateBetween(Long mechanicId, String status, LocalDateTime from, LocalDateTime to);

        @Query("select count(j) from JobCard j where j.appointment.assignedAdvisor.id = :advisorId " +
            "and j.assignedDate >= :from and j.assignedDate < :to")
        long countAssignedForAdvisorBetween(@Param("advisorId") Long advisorId,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

        @Query("select count(j) from JobCard j where j.appointment.assignedAdvisor.id = :advisorId " +
            "and j.status = 'DELIVERED'")
        long countCompletedForAdvisor(@Param("advisorId") Long advisorId);

        @Query("select count(j) from JobCard j where j.appointment.assignedAdvisor.id = :advisorId " +
            "and upper(coalesce(j.status, '')) not in ('DELIVERED', 'CANCELLED', 'CANCELED')")
        long countActiveAssignmentsForAdvisor(@Param("advisorId") Long advisorId);

            @Query("select count(distinct j.id) from JobCard j left join j.assignedMechanics assigned " +
                "where j.appointment.assignedAdvisor.id = :advisorId " +
                "and (j.mechanic is not null or assigned.id is not null) " +
                "and upper(coalesce(j.status, '')) not in ('DELIVERED', 'CANCELLED', 'CANCELED')")
            long countActiveMechanicWorkloadForAdvisor(@Param("advisorId") Long advisorId);

            @Query("select j from JobCard j left join j.appointment appointment left join appointment.assignedAdvisor advisor " +
                "where appointment is null or advisor is null or advisor.id = :advisorId")
        Page<JobCard> findVisibleToAdvisor(@Param("advisorId") Long advisorId, Pageable pageable);

        @Query("select count(j) from JobCard j where j.appointment.assignedAdvisor.id = :advisorId " +
            "and j.assignedDate >= :from and j.assignedDate < :to " +
            "and upper(coalesce(j.status, '')) not in ('DELIVERED', 'CANCELLED', 'CANCELED')")
        long countActiveForAdvisor(@Param("advisorId") Long advisorId,
                       @Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to);

        @Query("select count(j) from JobCard j where j.appointment.assignedAdvisor.id = :advisorId " +
            "and j.status = 'DELIVERED' and j.completedDate >= :from and j.completedDate < :to")
        long countCompletedForAdvisor(@Param("advisorId") Long advisorId,
                      @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to);

        @Query("select distinct j from JobCard j left join j.assignedMechanics assigned " +
            "where j.status = 'DELIVERED' and j.completedDate >= :from and j.completedDate < :to " +
            "and (j.mechanic.id = :mechanicId or assigned.id = :mechanicId)")
        java.util.List<JobCard> findCompletedForMechanicBetween(@Param("mechanicId") Long mechanicId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

        @Query("select j from JobCard j where j.status = 'DELIVERED' and j.completedDate >= :from and j.completedDate < :to")
        java.util.List<JobCard> findCompletedBetween(@Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);

        @Query("select count(distinct j.id) from JobCard j left join j.assignedMechanics assigned " +
            "where (j.mechanic is not null or assigned.id is not null) " +
            "and upper(coalesce(j.status, '')) not in ('DELIVERED', 'CANCELLED')")
        long countActiveAssignedJobs();

        @Query("select distinct j from JobCard j left join j.assignedMechanics assigned " +
            "where j.mechanic.id = :mechanicId or assigned.id = :mechanicId")
        Page<JobCard> findAssignedToMechanic(@Param("mechanicId") Long mechanicId, Pageable pageable);

        @Query("select distinct j from JobCard j left join j.assignedMechanics assigned " +
            "where j.mechanic.id = :mechanicId or assigned.id = :mechanicId")
        java.util.List<JobCard> findAllAssignedToMechanic(@Param("mechanicId") Long mechanicId);

        @Query("select count(distinct j.id) from JobCard j left join j.assignedMechanics assigned " +
            "where (j.mechanic.id = :mechanicId or assigned.id = :mechanicId) " +
            "and upper(coalesce(j.status, '')) not in ('DELIVERED', 'CANCELLED')")
        long countActiveAssignments(@Param("mechanicId") Long mechanicId);

}
