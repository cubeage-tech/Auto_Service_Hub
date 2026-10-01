package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.MechanicRequestDTO;
import com.autoservicehub.dto.MechanicResponseDTO;
import com.autoservicehub.dto.MechanicSkillRequestDTO;
import com.autoservicehub.dto.MechanicSkillResponseDTO;
import com.autoservicehub.dto.AttendanceResponseDTO;
import com.autoservicehub.dto.MechanicWorkloadResponseDTO;
import com.autoservicehub.service.AttendanceService;
import com.autoservicehub.service.MechanicService;
import com.autoservicehub.service.MechanicSkillService;
import com.autoservicehub.service.MechanicWorkloadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Mechanic Management (SRS 4.6)
 * Base path: /api/v1/mechanics
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/mechanics")
@RequiredArgsConstructor
public class MechanicController {

    private final MechanicService service;
    private final MechanicSkillService skillService;
    private final AttendanceService attendanceService;
    private final MechanicWorkloadService workloadService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MechanicResponseDTO> create(@Valid @RequestBody MechanicRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @GetMapping("/{mechanicId}/skills")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<List<MechanicSkillResponseDTO>> skills(@PathVariable Long mechanicId) {
        return ApiResponse.ok(skillService.list(mechanicId));
    }

    @PostMapping("/{mechanicId}/skills")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MechanicSkillResponseDTO> addSkill(@PathVariable Long mechanicId,
                                                          @Valid @RequestBody MechanicSkillRequestDTO request) {
        return ApiResponse.ok("Created", skillService.create(mechanicId, request));
    }

    @PutMapping("/{mechanicId}/skills/{skillId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ApiResponse<MechanicSkillResponseDTO> updateSkill(@PathVariable Long mechanicId,
                                                             @PathVariable Long skillId,
                                                             @Valid @RequestBody MechanicSkillRequestDTO request) {
        return ApiResponse.ok("Updated", skillService.update(mechanicId, skillId, request));
    }

    @DeleteMapping("/{mechanicId}/skills/{skillId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable Long mechanicId, @PathVariable Long skillId) {
        skillService.delete(mechanicId, skillId);
    }

    @PostMapping("/{mechanicId}/attendance/check-in")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MECHANIC')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttendanceResponseDTO> checkIn(@PathVariable Long mechanicId) {
        return ApiResponse.ok("Checked in", attendanceService.checkIn(mechanicId));
    }

    @PostMapping("/{mechanicId}/attendance/check-out")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MECHANIC')")
    public ApiResponse<AttendanceResponseDTO> checkOut(@PathVariable Long mechanicId) {
        return ApiResponse.ok("Checked out", attendanceService.checkOut(mechanicId));
    }

    @GetMapping("/{mechanicId}/attendance")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MECHANIC')")
    public ApiResponse<List<AttendanceResponseDTO>> attendanceHistory(@PathVariable Long mechanicId) {
        return ApiResponse.ok(attendanceService.history(mechanicId));
    }

    @GetMapping("/me/workload")
    @PreAuthorize("hasRole('MECHANIC')")
    public ApiResponse<MechanicWorkloadResponseDTO> myWorkload() {
        return ApiResponse.ok(workloadService.getMine());
    }

    @GetMapping("/{mechanicId}/workload")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<MechanicWorkloadResponseDTO> workload(@PathVariable Long mechanicId) {
        return ApiResponse.ok(workloadService.getForMechanic(mechanicId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ApiResponse<MechanicResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody MechanicRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('MECHANIC')")
    public ApiResponse<MechanicResponseDTO> getMine() {
        return ApiResponse.ok(service.getMine());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<MechanicResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<MechanicResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
