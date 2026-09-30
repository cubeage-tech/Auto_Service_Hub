package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-REP-2: one mechanic's performance for a period.
 *
 * <p>Every metric is a count or sum of real JobCard / Invoice / Feedback rows.
 *
 * <p>What this schema does <em>not</em> contain, and so what this report does
 * not claim: hours worked, shifts, capacity or utilisation. There is no
 * time-tracking table, so productivity per hour cannot be computed and is not
 * estimated here.
 *
 * <p>Completion rate is completed / assigned, not completed / (completed +
 * open), so it reflects work actually finished in the window and is not skewed
 * by jobs still open at the report date.
 */
@Getter
@Setter
public class MechanicPerformanceReportDTO {

    private LocalDate from;
    private LocalDate to;

    private Long mechanicId;
    private String mechanicName;

    /** Matches Mechanic.employeeCode, which is a String column. */
    private String employeeCode;

    /** Job cards assigned to this mechanic inside the window. */
    private long assignedJobs;

    /** Of those, the ones that reached the terminal delivered state. */
    private long completedJobs;

    /** Of those, still in a non-terminal state at the end of the window. */
    private long openJobs;

    /** completedJobs / assignedJobs, or zero when nothing was assigned. */
    private BigDecimal completionRate = BigDecimal.ZERO;

    /** Sum of invoice totals for this mechanic's completed jobs in the window. */
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    /** totalRevenue / completedJobs, or zero when nothing completed. */
    private BigDecimal averageRevenuePerJob = BigDecimal.ZERO;

    /**
     * Mean customer rating, or null when nobody has rated this mechanic.
     *
     * <p>Null is deliberate: "not rated" and "rated zero" are different facts,
     * and collapsing them would make an unrated mechanic look like the worst in
     * the workshop.
     */
    private Double averageCustomerRating;

    /** How many ratings that average is based on. */
    private long ratingCount;

    /** Metrics this schema cannot supply, named so they are not assumed computed. */
    private String unsupportedMetrics;
}


