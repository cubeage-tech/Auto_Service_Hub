package com.autoservicehub.service;

import com.autoservicehub.dto.AttendanceResponseDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Mechanic attendance — check-in, check-out and retrieval (SRS FR-MECH-2,
 * "record attendance/check-in/check-out"; {@code attendance} in SRS 8.2/8.3).
 *
 * <p>Deliberately minimal: the three operations the requirement names, and nothing
 * that would require a business rule the SRS does not state (no shift definitions,
 * no overtime thresholds, no leave handling).
 */
public interface AttendanceService {

    /**
     * Records a check-in for a mechanic on a day.
     *
     * <p>Refuses a second check-in for the same mechanic and day.
     */
    AttendanceResponseDTO checkIn(Long mechanicId, LocalDate date);

    /**
     * Records a check-out against an existing check-in.
     *
     * <p>Refuses a check-out for a day with no check-in, and a second check-out.
     */
    AttendanceResponseDTO checkOut(Long mechanicId, LocalDate date);

    /** Attendance across the window, newest first. A null mechanic id means all. */
    List<AttendanceResponseDTO> list(Long mechanicId, LocalDate from, LocalDate to);
}