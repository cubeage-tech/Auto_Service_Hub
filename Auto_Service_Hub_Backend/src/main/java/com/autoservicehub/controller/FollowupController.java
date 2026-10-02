package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.service.FollowupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Customer Follow-up & Retention (SRS 4.10)
 * Base path: /api/v1/followups
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/followups")
@RequiredArgsConstructor
public class FollowupController {

    private final FollowupService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FollowupResponseDTO> create(@Valid @RequestBody FollowupRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<FollowupResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody FollowupRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<FollowupResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<FollowupResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Open follow-ups, oldest due date first.
     * GET /api/v1/followups/pending?page=0&size=20
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<FollowupResponseDTO>> listPending(Pageable pageable) {
        return ApiResponse.ok(service.listPending(pageable));
    }

    /**
     * Open follow-ups due on or before the given date (today when omitted).
     * GET /api/v1/followups/due?asOf=2026-10-01
     */
    @GetMapping("/due")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<FollowupResponseDTO>> listDue(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            Pageable pageable) {
        return ApiResponse.ok(service.listDue(asOf != null ? asOf : LocalDate.now(), pageable));
    }

    /**
     * Follow-ups belonging to one customer.
     * GET /api/v1/followups/customer/{customerId}?page=0&size=20
     */
    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<FollowupResponseDTO>> listByCustomer(
            @PathVariable Long customerId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByCustomer(customerId, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
