package com.autoservicehub.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-REP-5: Customer growth over a period.
 *
 * <p>Two distinct populations, and the distinction matters:
 * <ul>
 *   <li>{@code newCustomerCount} — customers whose {@code created_at} falls in the
 *       period. This is the only true "growth" figure the data supports.</li>
 *   <li>{@code repeatCustomerCount} — customers who already existed before the
 *       period but returned during it for a job card. Derived from JobCard rows,
 *       not from the customer table.</li>
 * </ul>
 *
 * <p>{@code repeatRate} is repeat customers as a share of customers who were
 * served in the period (new + repeat). It is null when nobody was served, since
 * a rate over no activity would read as 0% and imply churn that did not happen.
 */
@Getter
@Setter
@AllArgsConstructor
public class CustomerGrowthReportDTO {

    private LocalDate from;
    private LocalDate to;

    /** Customers created inside the period. */
    private long newCustomerCount;

    /** Total customers on record at the end of the period. */
    private long totalCustomersAtEndOfPeriod;

    /** Customers created before the period who had a job card in it. */
    private long repeatCustomerCount;

    /** Customers with at least one job card in the period (new + returning). */
    private long customersServedInPeriod;

    /** Job cards raised in the period, for context against the counts above. */
    private long jobsInPeriod;

    /** repeatCustomerCount / customersServedInPeriod, or null if none served. */
    private BigDecimal repeatRate;

    /** Caveats a reader needs to interpret the figures correctly. */
    private String notes;
}

