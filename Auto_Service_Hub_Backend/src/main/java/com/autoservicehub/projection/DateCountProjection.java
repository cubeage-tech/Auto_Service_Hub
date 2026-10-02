package com.autoservicehub.projection;

import java.time.LocalDateTime;

/**
 * A count of rows sharing one timestamp (FR-REP-5 customer growth).
 *
 * <p>The queries group by the raw {@code createdAt} timestamp rather than by
 * day or month. JPQL has no portable date-truncation function — the usual
 * {@code DATE()} / {@code CONVERT()} spellings are database-specific — so the
 * grouping stops here and the reporting layer folds the timestamps into days or
 * months. That keeps one query in the database instead of loading every row,
 * without hard-coding a dialect.
 */
public interface DateCountProjection {

    LocalDateTime getCreatedAt();

    /** Number of rows sharing that timestamp. */
    Long getRowCount();
}
