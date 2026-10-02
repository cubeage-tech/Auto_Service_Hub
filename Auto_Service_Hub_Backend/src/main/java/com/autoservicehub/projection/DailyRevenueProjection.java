package com.autoservicehub.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A day's invoiced value and count, for a revenue trend over a date range.
 *
 * <p>Grouping in the database keeps the whole trend to one query; doing it in
 * Java would mean loading every invoice row in the period.
 */
public interface DailyRevenueProjection {

    LocalDate getInvoiceDate();

    /** Sum of invoice totals for that date. */
    BigDecimal getTotal();

    /** Number of invoices dated that day. */
    Long getInvoiceCount();
}
