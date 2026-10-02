package com.autoservicehub.repository;

import com.autoservicehub.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Attendance. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long>, JpaSpecificationExecutor<Attendance> {

    /**
     * The attendance row for one mechanic on one day, or empty when they have not
     * checked in. Existence of this single row is what makes check-in a
     * create-or-update rather than an append (FR-MECH-2).
     */
    Optional<Attendance> findByMechanicIdAndAttendanceDate(Long mechanicId, LocalDate attendanceDate);

    /**
     * Attendance for the given mechanics, newest day first (FR-MECH-2 retrieval).
     *
     * <p>Half-open on the date so the whole of {@code to} is included without
     * sub-second arithmetic, matching the report window convention.
     */
    List<Attendance> findByAttendanceDateBetweenAndMechanicIdInOrderByAttendanceDateDescIdDesc(
            LocalDate from, LocalDate to, List<Long> mechanicIds);

    /** The same window across every mechanic, for the management view. */
    List<Attendance> findByAttendanceDateBetweenOrderByAttendanceDateDescIdDesc(
            LocalDate from, LocalDate to);
}
