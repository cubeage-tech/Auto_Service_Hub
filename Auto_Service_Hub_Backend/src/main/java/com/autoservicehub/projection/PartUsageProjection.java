package com.autoservicehub.projection;

import java.math.BigDecimal;

/**
 * Parts consumption rolled up per part, from OUT stock movements only.
 *
 * <p>{@code purchasePrice} is the part's <em>current</em> purchase price: the
 * schema stores no cost as-of the date a movement happened, so any cost derived
 * from it is an approximation and is reported as such.
 *
 * <p>{@code quantity} is the sum of the OUT movement quantities. It is returned
 * as a {@code Long} because a SQL SUM over an INT column is wider than the
 * column type, and reading it into an Integer would overflow on large totals.
 */
public interface PartUsageProjection {

    Long   getPartId();

    String getSku();

    String getName();

    String getUnit();

    /** Total units consumed. */
    Long getQuantity();

    /** Number of OUT movements behind that total. */
    Long getMovementCount();

    /** The part's current purchase price; may be null if never costed. */
    BigDecimal getPurchasePrice();
}
