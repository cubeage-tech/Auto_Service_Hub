package com.autoservicehub.projection;

import java.time.LocalDateTime;

/**
 * One completed job's dates, used to derive turnaround time (FR-MECH-5).
 *
 * <p>Only jobs that reached the terminal delivered state contribute: a job that is
 * still open, or that never started, has no completion to measure against, and
 * including it would drag the average towards zero and make a slow workshop look
 * fast.
 *
 * <p>Both timestamps are returned rather than their difference so the arithmetic
 * stays in Java, where {@code ChronoUnit} is exact and portable. There is no
 * time-tracking table involved (FR-MECH-5, SRS 8.2): the dates already stored on
 * the job card are the whole basis.
 */
public interface MechanicTurnaroundProjection {

    Long getMechanicId();

    LocalDateTime getAssignedDate();

    LocalDateTime getCompletedDate();
}