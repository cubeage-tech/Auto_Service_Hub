package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * FR-REP-7: the filters the current schema can genuinely support, in one place.
 *
 * <p>Only filters that map to a stored column or relation are offered. There is
 * deliberately no {@code minAmount} / {@code jobStatus} free-text field here that
 * the database could not honour — see {@link #JOB_STATUS} for how status is
 * handled.
 *
 * <p>Every filter is optional and nullable; null means "do not filter on this".
 */
@Getter
@Setter
public class ReportFilterDTO {

    /**
     * Statuses treated as finished. Job cards in any other state are still open,
     * which is what makes the completed/pending split meaningful.
     */
    public static final String JOB_STATUS_DELIVERED = "DELIVERED";

    private LocalDate from;
    private LocalDate to;

    /** Filter by assigned mechanic. */
    private Long mechanicId;

    /** Filter by the serviced vehicle. */
    private Long vehicleId;

    /** Filter by service type, matched case-insensitively. */
    private String serviceType;

    /** Filter by an exact job-card status, e.g. DELIVERED or IN_REPAIR. */
    private String status;

    /** The date the report is about; defaults to today when unset. */
    private LocalDate date;

    /** Restrict parts usage to one job card. Supported by the repository layer. */
    private Long jobCardId;

    /** Inclusive lower bound as a timestamp, for {@code LocalDateTime} columns. */
    public java.time.LocalDateTime fromDateTime() {
        return startOf(from);
    }

    /**
     * Inclusive upper bound as an exclusive timestamp. Using {@code to + 1 day}
     * as the start of the next day keeps the range inclusive of the whole of the
     * {@code to} date, which a plain {@code atTime(23:59:59.999...)} would not.
     */
    public java.time.LocalDateTime toDateTimeExclusive() {
        return startOf(to == null ? null : to.plusDays(1));
    }

    public boolean isDateRangeValid() {
        return from == null || to == null || !from.isAfter(to);
    }

    private static java.time.LocalDateTime startOf(LocalDate d) {
        return d == null ? null : d.atStartOfDay();
    }
}
