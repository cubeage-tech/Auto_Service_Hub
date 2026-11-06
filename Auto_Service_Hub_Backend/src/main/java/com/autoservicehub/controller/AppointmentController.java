package com.autoservicehub.controller;

import com.autoservicehub.dto.AdvisorResponseDTO;
import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Appointment & Booking (SRS 4.3)
 * Base path: /api/v1/appointments
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService service;

    /**
     * Static sub-paths of this controller, declared before the id routes.
     *
     * <p>The id routes below are constrained to {@code \d+}, so "advisors",
     * "bays" and "types" can never be handed to a {@code Long} path variable.
     * Before that constraint existed, such a request fell through to
     * {@code @GetMapping("/{id}")}, Spring could not convert the segment, and
     * the caller received 400 "Parameter 'id' has an invalid value." — an
     * error that pointed at the wrong thing entirely.
     */
    @GetMapping("/bays")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<List<String>> bays() {
        return ApiResponse.ok(service.availableBaySlots());
    }

    /**
     * Active users eligible to be assigned as an appointment's advisor.
     *
     * <p>Restricted to the same roles that may create or update an
     * appointment, since this list exists only to populate that picker.
     */
    @GetMapping("/advisors")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<List<AdvisorResponseDTO>> advisors() {
        return ApiResponse.ok(service.listAdvisors());
    }

    /**
     * Booking types the appointment form may submit.
     *
     * <p>Mirrors the {@code /bays} endpoint so the picker cannot offer a value
     * the service would then reject.
     */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<List<String>> types() {
        return ApiResponse.ok(service.supportedAppointmentTypes());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AppointmentResponseDTO> create(@Valid @RequestBody AppointmentRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<AppointmentResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody AppointmentRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<AppointmentResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<AppointmentResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
