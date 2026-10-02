package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Check-in / check-out request (FR-MECH-2).
 *
 * <p>Only the mechanic and the day are supplied. The times themselves are stamped
 * server-side from the clock: a client-sent check-in time would let anyone record
 * an attendance fact that never happened, and attendance is exactly the kind of
 * record that must not be forgeable.
 */
@Getter
@Setter
public class AttendanceCheckRequestDTO {

    @NotNull(message = "mechanicId is required.")
    private Long mechanicId;

    /**
     * The day being recorded. Defaults to today when omitted, which is the normal
     * case for a front-desk terminal.
     */
    private LocalDate date;
}