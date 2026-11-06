package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.PackageApplicationRequestDTO;
import com.autoservicehub.dto.PackageApplicationResponseDTO;
import com.autoservicehub.service.PackageApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Applying service packages to customers (SRS 4.8).
 * Base path: /api/v1/package-applications
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 *
 * <p>A separate resource from {@code /api/v1/service-packages} rather than a
 * sub-path of it: applications are a different record with a different
 * lifecycle, and keeping the catalogue's CRUD free of them means the catalogue
 * can be edited, repriced or withdrawn without touching what was already sold.
 */
@RestController
@RequestMapping("/api/v1/package-applications")
@RequiredArgsConstructor
public class PackageApplicationController {

    private final PackageApplicationService service;

    /**
     * Apply a package to a customer's vehicle. The price, GST rate, discount and
     * duration are copied from the package server-side and returned as stored.
     * POST /api/v1/package-applications
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PackageApplicationResponseDTO> apply(
            @Valid @RequestBody PackageApplicationRequestDTO request) {
        return ApiResponse.ok("Applied", service.apply(request));
    }

    /** Every application, newest first. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PackageApplicationResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Applications of one package, newest first.
     * GET /api/v1/package-applications/package/{servicePackageId}
     */
    @GetMapping("/package/{servicePackageId}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PackageApplicationResponseDTO>> listByPackage(
            @PathVariable Long servicePackageId, Pageable pageable) {
        return ApiResponse.ok(service.listByPackage(servicePackageId, pageable));
    }

    /** One application by id. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<PackageApplicationResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }
}
