package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.AttendanceCheckRequestDTO;
import com.autoservicehub.dto.AttendanceResponseDTO;
import com.autoservicehub.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Mechanic Attendance (SRS FR-MECH-2, section 4.6)
 * Base path: /api/v1/attendance
 *
 * <p>Roles follow the SRS 13 permission matrix. A mechanic records their own
 * attendance, so MECHANIC is allowed to check in and out; SERVICE_ADVISOR and
 * management can do the same on a mechanic's behalf from the front desk. Nothing
 * here grants the ability to edit a stored record — the times are facts.
 */
@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService service;

    /**
     * FR-MECH-2: record a check-in.
     * POST /api/v1/attendance/check-in
     */
    @PostMapping("/check-in")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttendanceResponseDTO> checkIn(
            @Valid @RequestBody AttendanceCheckRequestDTO request) {
        return ApiResponse.ok("Checked in", service.checkIn(request.getMechanicId(), request.getDate()));
    }

    /**
     * FR-MECH-2: record a check-out.
     * POST /api/v1/attendance/check-out
     */
    @PostMapping("/check-out")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<AttendanceResponseDTO> checkOut(
            @Valid @RequestBody AttendanceCheckRequestDTO request) {
        return ApiResponse.ok("Checked out", service.checkOut(request.getMechanicId(), request.getDate()));
    }

    /**
     * FR-MECH-2: retrieve attendance for a mechanic (or all mechanics) over a
     * date range. Omitting the range means today.
     * GET /api/v1/attendance?mechanicId=&from=&to=
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<List<AttendanceResponseDTO>> list(
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.list(mechanicId, from, to));
    }
}