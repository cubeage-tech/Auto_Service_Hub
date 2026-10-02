package com.autoservicehub.repository;

import com.autoservicehub.entity.JobCard;
import com.autoservicehub.projection.MechanicJobCountProjection;
import com.autoservicehub.projection.MechanicTurnaroundProjection;
import com.autoservicehub.projection.StatusCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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

    // ── Existing dashboard/report queries ──────────────────────────────────
    long countByStatus(String status);

    /**
     * Job cards assigned inside a window, counted with the half-open convention
     * {@code [from, to)} used by every other report query in this repository.
     *
     * <p>Written as explicit JPQL rather than a derived {@code Between} method on
     * purpose. Spring Data's {@code Between} is inclusive of <em>both</em> bounds,
     * so a job assigned at exactly {@code to} — for the dashboard that is exactly
     * midnight tonight — was counted in the current day's total. Callers already
     * pass an exclusive upper bound ({@code ReportFilterDTO#toDateTimeExclusive()}),
     * so the derived form silently contradicted them.
     *
     * <p>The signature is unchanged, so every caller keeps working; only the
     * boundary handling is corrected.
     */
    @Query("SELECT COUNT(j.id) FROM JobCard j "
         + "WHERE j.assignedDate >= :from AND j.assignedDate < :to")
    long countByAssignedDateBetween(@Param("from") LocalDateTime from,
                                    @Param("to")   LocalDateTime to);
    long countByStatusAndAssignedDateBetween(String status, LocalDateTime from, LocalDateTime to);
    long countByMechanicIdAndStatusAndAssignedDateBetween(Long mechanicId, String status,
                                                          LocalDateTime from, LocalDateTime to);

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

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-7: Report queries
    //
    // Date windows are half-open, [from, to). Callers pass the start of the
    // 'from' day and the start of the day AFTER 'to', so the whole of the 'to'
    // day is included without depending on sub-second precision.
    //
    // Optional filters are written as "(:x IS NULL OR ...)" rather than as a
    // separate method per combination, so one query serves both the filtered and
    // unfiltered case and the number of statements stays bounded.
    // ══════════════════════════════════════════════════════════════════════

    /** The constant for a finished job, kept next to the queries that use it. */
    String STATUS_DELIVERED = "DELIVERED";

    // ── Daily workshop (FR-REP-1) ─────────────────────────────────────────

    /** Job cards assigned in the window, oldest first. */
    List<JobCard> findByAssignedDateBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);

    /**
     * Every job-card status in the window with its count.
     *
     * <p>The counts sum to the same total as {@link #countByAssignedDateBetween},
     * which is what lets a client sanity-check the breakdown against the whole.
     * Ordered by status so the output is stable between runs.
     */
    @Query("SELECT j.status AS status, COUNT(j) AS statusCount FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to " +
           "GROUP BY j.status ORDER BY j.status ASC")
    List<StatusCountProjection> countGroupedByStatus(@Param("from") LocalDateTime from,
                                                     @Param("to")   LocalDateTime to);

    /**
     * The same breakdown with the report filters applied.
     */
    @Query("SELECT j.status AS status, COUNT(j) AS statusCount FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to " +
           "AND (:mechanicId IS NULL OR j.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR j.vehicle.id  = :vehicleId) " +
           "AND (:serviceType IS NULL OR UPPER(j.serviceType) = UPPER(:serviceType)) " +
           "AND (:status IS NULL OR UPPER(j.status) = UPPER(:status)) " +
           "GROUP BY j.status ORDER BY j.status ASC")
    List<StatusCountProjection> countGroupedByStatusWithFilters(
            @Param("from")        LocalDateTime from,
            @Param("to")          LocalDateTime to,
            @Param("mechanicId")  Long         mechanicId,
            @Param("vehicleId")   Long         vehicleId,
            @Param("serviceType") String       serviceType,
            @Param("status")      String       status);

    /** Job cards matching the filters, for export and for drill-down. */
    @Query("SELECT j FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to " +
           "AND (:mechanicId IS NULL OR j.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR j.vehicle.id  = :vehicleId) " +
           "AND (:serviceType IS NULL OR UPPER(j.serviceType) = UPPER(:serviceType)) " +
           "AND (:status IS NULL OR UPPER(j.status) = UPPER(:status)) " +
           "ORDER BY j.assignedDate ASC, j.id ASC")
    List<JobCard> findForReport(@Param("from")        LocalDateTime from,
                                @Param("to")          LocalDateTime to,
                                @Param("mechanicId")  Long         mechanicId,
                                @Param("vehicleId")   Long         vehicleId,
                                @Param("serviceType") String       serviceType,
                                @Param("status")      String       status);

    /** Total matching the filters — the denominator for the status breakdown. */
    @Query("SELECT COUNT(j) FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to " +
           "AND (:mechanicId IS NULL OR j.mechanic.id = :mechanicId) " +
           "AND (:vehicleId  IS NULL OR j.vehicle.id  = :vehicleId) " +
           "AND (:serviceType IS NULL OR UPPER(j.serviceType) = UPPER(:serviceType)) " +
           "AND (:status IS NULL OR UPPER(j.status) = UPPER(:status))")
    long countForReport(@Param("from")        LocalDateTime from,
                        @Param("to")          LocalDateTime to,
                        @Param("mechanicId")  Long         mechanicId,
                        @Param("vehicleId")   Long         vehicleId,
                        @Param("serviceType") String       serviceType,
                        @Param("status")      String       status);

    /** Job cards in the window that are NOT in the given (terminal) status. */
    @Query("SELECT COUNT(j) FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to " +
           "AND UPPER(j.status) <> UPPER(:excludedStatus)")
    long countByAssignedDateRangeAndStatusNot(@Param("from")           LocalDateTime from,
                                              @Param("to")             LocalDateTime to,
                                              @Param("excludedStatus") String       excludedStatus);

    // ── Per-mechanic and per-vehicle filters ─────────────────────────────

    /** Job cards for one mechanic in the window, oldest first. */
    List<JobCard> findByMechanicIdAndAssignedDateBetweenOrderByIdAsc(
            Long mechanicId, LocalDateTime from, LocalDateTime to);

    /** Job cards for one vehicle in the window, oldest first. */
    List<JobCard> findByVehicleIdAndAssignedDateBetweenOrderByIdAsc(
            Long vehicleId, LocalDateTime from, LocalDateTime to);

    /** Distinct service types actually present, for populating the filter UI. */
    @Query("SELECT DISTINCT j.serviceType FROM JobCard j " +
           "WHERE j.serviceType IS NOT NULL ORDER BY j.serviceType ASC")
    List<String> findDistinctServiceTypes();

    /** Distinct statuses actually present, for populating the filter UI. */
    @Query("SELECT DISTINCT j.status FROM JobCard j " +
           "WHERE j.status IS NOT NULL ORDER BY j.status ASC")
    List<String> findDistinctStatuses();

    // ── Customer growth (FR-REP-5) ───────────────────────────────────────

    /**
     * Distinct customers who had at least one job card in the window.
     *
     * <p>COUNT(DISTINCT) rather than COUNT: a customer with three jobs in the
     * period is one served customer, not three. The null check on customer is
     * because JobCard.customer is nullable, and counting nulls would inflate
     * the figure with jobs that belong to nobody.
     */
    @Query("SELECT COUNT(DISTINCT j.customer.id) FROM JobCard j " +
           "WHERE j.customer.id IS NOT NULL " +
           "AND j.assignedDate >= :from AND j.assignedDate < :to")
    long countDistinctCustomersWithJobs(@Param("from") LocalDateTime from,
                                        @Param("to")   LocalDateTime to);

    /**
     * Customers who existed BEFORE the window but returned during it.
     *
     * <p>This is the repeat-customer measure: pre-existing plus a job card in the
     * period. Comparing {@code createdAt} against the window start is what
     * separates "came back" from "signed up and used us" — counting both as
     * repeat business would inflate the number with first-time customers.
     */
    @Query("SELECT COUNT(DISTINCT j.customer.id) FROM JobCard j " +
           "WHERE j.customer.id IS NOT NULL " +
           "AND j.customer.createdAt < :from " +
           "AND j.assignedDate >= :from AND j.assignedDate < :to")
    long countRepeatCustomersInPeriod(@Param("from") LocalDateTime from,
                                      @Param("to")   LocalDateTime to);

    /** Total job cards raised in the window, for context against the counts. */
    @Query("SELECT COUNT(j) FROM JobCard j " +
           "WHERE j.assignedDate >= :from AND j.assignedDate < :to")
    long countByAssignedDateRange(@Param("from") LocalDateTime from,
                                  @Param("to")   LocalDateTime to);

    // ── Mechanic performance (FR-REP-2) ─────────────────────────────────

    /**
     * Assigned and completed job counts per mechanic, over the window.
     *
     * <p>A LEFT JOIN from Mechanic is used so a mechanic with no jobs in the
     * window still appears with zero counts. An inner join would drop them, and
     * a mechanic missing from a performance report reads as "not measured"
     * rather than "measured, did nothing" — which understates the problem.
     *
     * <p>The date range sits in the ON clause, not the WHERE, for the same
     * reason: putting it in the WHERE would turn the LEFT JOIN back into an
     * inner join and discard idle mechanics.
     */
    @Query("SELECT m.id AS mechanicId, m.name AS mechanicName, m.employeeCode AS employeeCode, " +
           "       COUNT(j.id) AS assignedJobs, " +
           "       SUM(CASE WHEN UPPER(j.status) = UPPER(:completedStatus) THEN 1 ELSE 0 END) " +
           "         AS completedJobs " +
           "FROM Mechanic m LEFT JOIN JobCard j " +
           "     ON j.mechanic.id = m.id AND j.assignedDate >= :from AND j.assignedDate < :to " +
           "WHERE (:mechanicId IS NULL OR m.id = :mechanicId) " +
           "GROUP BY m.id, m.name, m.employeeCode " +
           "ORDER BY COUNT(j.id) DESC, m.name ASC")
    List<MechanicJobCountProjection> countJobsGroupedByMechanic(
            @Param("from")           LocalDateTime from,
            @Param("to")             LocalDateTime to,
            @Param("mechanicId")     Long         mechanicId,
            @Param("completedStatus") String       completedStatus);

    /**
     * FR-MECH-5: the dates of every completed job in the window, for deriving
     * mechanic turnaround time.
     *
     * <p>Restricted to the terminal delivered state and to rows that actually
     * carry both dates: an open or cancelled job has no completion to measure, and
     * a missing date would have to be guessed at.
     *
     * <p>The two timestamps are returned rather than a pre-computed difference so
     * the arithmetic happens in Java with ChronoUnit, which is exact and portable
     * across the databases this project runs on. No new time-tracking table is
     * involved; the job card's own dates are the whole basis.
     */
    @Query("SELECT j.mechanic.id AS mechanicId, j.assignedDate AS assignedDate, "
         + "       j.completedDate AS completedDate "
         + "FROM JobCard j "
         + "WHERE j.mechanic IS NOT NULL "
         + "  AND j.assignedDate IS NOT NULL AND j.completedDate IS NOT NULL "
         + "  AND UPPER(j.status) = UPPER(:completedStatus) "
         + "  AND j.assignedDate >= :from AND j.assignedDate < :to "
         + "  AND (:mechanicId IS NULL OR j.mechanic.id = :mechanicId)")
    List<MechanicTurnaroundProjection> findCompletedTurnaroundInPeriod(
            @Param("from")           LocalDateTime from,
            @Param("to")             LocalDateTime to,
            @Param("mechanicId")     Long         mechanicId,
            @Param("completedStatus") String       completedStatus);

    // APPENDED_MORE_QUERIES
}
