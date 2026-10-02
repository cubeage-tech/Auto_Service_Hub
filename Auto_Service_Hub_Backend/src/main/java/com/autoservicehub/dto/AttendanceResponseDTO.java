package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One mechanic's attendance for one day (FR-MECH-2).
 *
 * <p>{@code hoursWorked} is derived, not stored: it is the difference between the
 * two stamped times, and storing a third copy could only ever disagree with them.
 * It is null while the day is still open — a half-finished shift has no duration.
 */
@Getter
@Setter
public class AttendanceResponseDTO {

    private Long id;
    private Long mechanicId;
    private String mechanicName;
    private LocalDate attendanceDate;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;

    /** Total duration in hours, or null until the day is checked out. */
    private Double hoursWorked;

    /** True while the mechanic is checked in and has not yet left. */
    private boolean open;
}