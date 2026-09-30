package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.CommunicationRequestDTO;
import com.autoservicehub.dto.CommunicationResponseDTO;
import com.autoservicehub.service.CommunicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Customer communication log (SRS FR-CRM-4).
 * Base path: /api/v1/communications
 *
 * Channels: CALL | WHATSAPP | EMAIL | REMINDER
 * Directions: INBOUND | OUTBOUND
 */
@RestController
@RequestMapping("/api/v1/communications")
@RequiredArgsConstructor
public class CommunicationController {

    private final CommunicationService service;

    /**
     * Log a new communication record.
     * POST /api/v1/communications
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommunicationResponseDTO> create(
            @Valid @RequestBody CommunicationRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    /**
     * Update a communication record.
     * PUT /api/v1/communications/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<CommunicationResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CommunicationRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    /**
     * Get a single communication record by ID.
     * GET /api/v1/communications/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<CommunicationResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /**
     * List communications for a specific customer (paged, newest first).
     * GET /api/v1/communications?customerId={id}&page=0&size=20&sort=sentAt,desc
     *
     * customerId is required — prevents loading the entire communications table.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<Page<CommunicationResponseDTO>> listByCustomer(
            @RequestParam Long customerId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByCustomer(customerId, pageable));
    }

    /**
     * Delete a communication record.
     * DELETE /api/v1/communications/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
