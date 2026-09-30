package com.autoservicehub.projection;

import java.math.BigDecimal;

/**
 * Payment count and value grouped by payment mode (CASH / CARD / UPI …).
 *
 * <p>Kept separate from {@link InvoiceStatusTotalProjection} rather than reusing
 * it: the fields coincide in shape, but naming the mode column "status" would
 * misdescribe the data, and a reader comparing the two queries would reasonably
 * conclude the wrong column had been aliased.
 */
public interface PaymentModeTotalProjection {

    /** The payment mode exactly as recorded, e.g. CASH. */
    String getMode();

    /** Number of payments in that mode. */
    Long getPaymentCount();

    /** Sum of amounts in that mode. */
    BigDecimal getTotal();
}