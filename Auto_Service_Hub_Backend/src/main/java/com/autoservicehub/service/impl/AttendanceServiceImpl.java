package com.autoservicehub.service.impl;

import com.autoservicehub.dto.AttendanceResponseDTO;
import com.autoservicehub.entity.Attendance;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AttendanceRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.AttendanceService;
import com.autoservicehub.service.MechanicAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final MechanicRepository mechanicRepository;
    private final MechanicAccessService accessService;

    @Override
    public AttendanceResponseDTO checkIn(Long mechanicId) {
        Mechanic mechanic = findActiveMechanicForUpdate(mechanicId);
        if (attendanceRepository.existsByMechanicIdAndCheckOutIsNull(mechanicId)) {
            throw new BusinessRuleException("Mechanic already has an open attendance record.");
        }
        LocalDateTime now = LocalDateTime.now();
        Attendance attendance = new Attendance();
        attendance.setMechanic(mechanic);
        attendance.setAttendanceDate(now.toLocalDate());
        attendance.setCheckIn(now);
        return toResponse(attendanceRepository.save(attendance));
    }

    @Override
    public AttendanceResponseDTO checkOut(Long mechanicId) {
        findMechanicForUpdate(mechanicId);
        Attendance attendance = attendanceRepository.findFirstByMechanicIdAndCheckOutIsNullOrderByCheckInDesc(mechanicId);
        if (attendance == null) {
            throw new BusinessRuleException("No open attendance record exists for this mechanic.");
        }
        attendance.setCheckOut(LocalDateTime.now());
        return toResponse(attendanceRepository.save(attendance));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceResponseDTO> history(Long mechanicId) {
        findMechanic(mechanicId);
        return attendanceRepository.findByMechanicIdOrderByAttendanceDateDescCheckInDesc(mechanicId)
                .stream().map(this::toResponse).toList();
    }

    private Mechanic findMechanic(Long mechanicId) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        accessService.assertCanAccess(mechanic);
        return mechanic;
    }

    private Mechanic findActiveMechanic(Long mechanicId) {
        Mechanic mechanic = findMechanic(mechanicId);
        if (!"ACTIVE".equalsIgnoreCase(mechanic.getStatus())) {
            throw new BusinessRuleException("Attendance is allowed only for active mechanics.");
        }
        return mechanic;
    }

    private Mechanic findActiveMechanicForUpdate(Long mechanicId) {
        Mechanic mechanic = findMechanicForUpdate(mechanicId);
        if (!"ACTIVE".equalsIgnoreCase(mechanic.getStatus())) {
            throw new BusinessRuleException("Attendance is allowed only for active mechanics.");
        }
        return mechanic;
    }

    private Mechanic findMechanicForUpdate(Long mechanicId) {
        Mechanic mechanic = mechanicRepository.findByIdForUpdate(mechanicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + mechanicId));
        accessService.assertCanAccess(mechanic);
        return mechanic;
    }

    private AttendanceResponseDTO toResponse(Attendance attendance) {
        AttendanceResponseDTO response = new AttendanceResponseDTO();
        response.setId(attendance.getId());
        response.setMechanicId(attendance.getMechanic().getId());
        response.setAttendanceDate(attendance.getAttendanceDate() == null ? LocalDate.now() : attendance.getAttendanceDate());
        response.setCheckIn(attendance.getCheckIn());
        response.setCheckOut(attendance.getCheckOut());
        return response;
    }
}