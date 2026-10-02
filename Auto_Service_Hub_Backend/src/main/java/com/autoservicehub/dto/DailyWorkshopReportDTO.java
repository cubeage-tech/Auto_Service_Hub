package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FR-REP-1: Today's workshop activity.
 *
 * <p>Every figure is a count of real JobCard rows; nothing here is estimated.
 * {@code totalJobs} is the number of job cards whose assigned date falls on
 * {@code date}, and the status breakdown sums to exactly that total, so a
 * client can verify the parts against the whole.
 */
@Getter
@Setter
public class DailyWorkshopReportDTO {

    /** The day being reported on (defaults to today). */
    private LocalDate date;

    /** Job cards assigned on {@link #date}. */
    private long totalJobs;

    /** Those that reached the terminal DELIVERED state. */
    private long completedJobs;

    /** Those still in a non-terminal state, i.e. awaiting or under way. */
    private long pendingJobs;

    /** Job-card status to count, covering exactly the rows in {@link #totalJobs}. */
    private Map<String, Long> statusBreakdown = new LinkedHashMap<>();

    /** Value of the invoices raised for jobs assigned on this date. */
    private BigDecimal invoicedAmount = BigDecimal.ZERO;

    /** Successful payments collected against those invoices. */
    private BigDecimal collectedAmount = BigDecimal.ZERO;

    /**
     * True profit needs the labour and parts cost actually incurred against each
     * job, and neither is stored against a JobCard. See the profit-analysis report
     * for the full explanation; this report therefore reports billed amounts only.
     */
    private String limitation;
}
