package com.autoservicehub.service.impl;

import com.autoservicehub.dto.AttendanceResponseDTO;
import com.autoservicehub.entity.Attendance;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AttendanceRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Attendance check-in/check-out (FR-MECH-2).
 *
 * <p>Three rules, all of them about not recording something that did not happen:
 *
 * <ul>
 *   <li>a day can be checked in only once;</li>
 *   <li>a day can only be checked out after a check-in — a check-out with no
 *       check-in would produce a duration from nothing;</li>
 *   <li>both times are stamped from the server clock, never taken from the
 *       request.</li>
 * </ul>
 *
 * <p>One row per mechanic per day, enforced by the unique constraint on the table
 * as well as here, so two concurrent check-ins cannot both succeed.
 */
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final MechanicRepository    mechanicRepository;

    @Override
    @Transactional
    public AttendanceResponseDTO checkIn(Long mechanicId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        requireMechanic(mechanicId);

        if (attendanceRepository.findByMechanicIdAndAttendanceDate(mechanicId, day).isPresent()) {
            throw new BusinessRuleException(
                    "Mechanic " + mechanicId + " has already checked in on " + day + ".");
        }

        Attendance attendance = new Attendance();
        attendance.setMechanic(mechanicRepository.getReferenceById(mechanicId));
        attendance.setAttendanceDate(day);
        attendance.setCheckIn(LocalDateTime.now());

        return toResponse(attendanceRepository.save(attendance));
    }

    @Override
    @Transactional
    public AttendanceResponseDTO checkOut(Long mechanicId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        requireMechanic(mechanicId);

        Attendance attendance = attendanceRepository.findByMechanicIdAndAttendanceDate(mechanicId, day)
                .orElseThrow(() -> new BusinessRuleException(
                        "Mechanic " + mechanicId + " has no check-in on " + day
                        + ", so there is nothing to check out of."));

        if (attendance.getCheckIn() == null) {
            throw new BusinessRuleException(
                    "Mechanic " + mechanicId + " has no check-in on " + day + ".");
        }
        if (attendance.getCheckOut() != null) {
            throw new BusinessRuleException(
                    "Mechanic " + mechanicId + " has already checked out on " + day + ".");
        }

        attendance.setCheckOut(LocalDateTime.now());
        return toResponse(attendanceRepository.save(attendance));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceResponseDTO> list(Long mechanicId, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end   = to   == null ? start : to;
        // Inclusive of both ends, expressed the same way the reports do.
        List<Attendance> rows = mechanicId == null
                ? attendanceRepository.findByAttendanceDateBetweenOrderByAttendanceDateDescIdDesc(
                        start, end.plusDays(1).minusDays(1))
                : attendanceRepository
                        .findByAttendanceDateBetweenAndMechanicIdInOrderByAttendanceDateDescIdDesc(
                                start, end.plusDays(1).minusDays(1), List.of(mechanicId));

        return rows.stream().map(this::toResponse).toList();
    }

    private void requireMechanic(Long mechanicId) {
        if (mechanicId == null) {
            throw new BusinessRuleException("mechanicId is required.");
        }
        if (!mechanicRepository.existsById(mechanicId)) {
            throw new ResourceNotFoundException("Mechanic not found: " + mechanicId);
        }
    }

    private AttendanceResponseDTO toResponse(Attendance attendance) {
        Mechanic mechanic = attendance.getMechanic();
        AttendanceResponseDTO dto = new AttendanceResponseDTO();
        dto.setId(attendance.getId());
        dto.setMechanicId(mechanic == null ? null : mechanic.getId());
        dto.setMechanicName(mechanic == null ? null : mechanic.getName());
        dto.setAttendanceDate(attendance.getAttendanceDate());
        dto.setCheckIn(attendance.getCheckIn());
        dto.setCheckOut(attendance.getCheckOut());
        dto.setOpen(attendance.getCheckIn() != null && attendance.getCheckOut() == null);
        // Derived, and null until the day is closed: an unfinished shift has no
        // duration, and reporting zero would read as "worked no hours".
        dto.setHoursWorked(attendance.getCheckIn() == null || attendance.getCheckOut() == null
                ? null
                : (double) ChronoUnit.MINUTES.between(
                        attendance.getCheckIn(), attendance.getCheckOut()) / 60.0);
        return dto;
    }
}