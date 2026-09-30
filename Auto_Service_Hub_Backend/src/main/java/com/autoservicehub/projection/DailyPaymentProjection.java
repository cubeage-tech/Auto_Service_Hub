package com.autoservicehub.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A bucket of successful payments sharing one payment timestamp.
 *
 * <p>Grouped on the raw {@code paidAt} rather than on a calendar date, because
 * JPQL has no portable date-truncation function — the usual
 * {@code DATE()} / {@code CONVERT()} spellings are database-specific. The
 * reporting layer folds these timestamps into days.
 *
 * <p>Note the accessor is {@code getPaidAt} and returns a {@code LocalDateTime}:
 * it must match the column type, or the projection fails to map at runtime.
 */
public interface DailyPaymentProjection {

    LocalDateTime getPaidAt();

    BigDecimal getTotal();

    Long getPaymentCount();
}
