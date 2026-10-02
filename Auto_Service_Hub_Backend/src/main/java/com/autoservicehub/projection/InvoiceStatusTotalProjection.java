package com.autoservicehub.projection;

import java.math.BigDecimal;

/**
 * Invoice count and value per status (FR-REP-4 profit analysis).
 *
 * <p>Lets the revenue side of the profit report be broken down by settlement
 * state — for example how much is still outstanding against PAID invoices' peers
 * — without loading the invoice rows themselves.
 */
public interface InvoiceStatusTotalProjection {

    String getStatus();

    /** Number of invoices in that status. */
    Long getInvoiceCount();

    /** Sum of invoice totals in that status; null-safe via COALESCE. */
    BigDecimal getTotal();
}
