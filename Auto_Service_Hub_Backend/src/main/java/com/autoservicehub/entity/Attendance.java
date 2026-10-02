package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps to the 'attendance' table (SRS section 8.2 High-Level Entities).
 *
 * <p>FR-MECH-2 ("record attendance/check-in/check-out") and SRS 8.3 both scope
 * attendance to a mechanic, so the mechanic relation is mandatory here: an
 * attendance row that belongs to nobody could never be retrieved by any of the
 * queries the requirement implies.
 *
 * <p>One row per mechanic per day is enforced by a unique constraint rather than
 * only in the service, so two simultaneous check-in requests cannot both create a
 * row — the check-in time is a fact, and two of them is a data fault (SRS 23:
 * "duplicate checks" as a mitigation for bad data quality).
 */
@Getter
@Setter
@Entity
@Table(name = "attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attendance_mechanic_date",
                columnNames = {"mechanic_id", "attendance_date"}))
public class Attendance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private Mechanic mechanic;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "check_in")
    private LocalDateTime checkIn;

    @Column(name = "check_out")
    private LocalDateTime checkOut;
}
