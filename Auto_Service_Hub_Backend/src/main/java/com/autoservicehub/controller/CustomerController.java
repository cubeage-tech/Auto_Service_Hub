package com.autoservicehub.controller;

import com.autoservicehub.dto.*;
import com.autoservicehub.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer CRM (SRS 4.1, FR-CRM-1 through FR-CRM-7).
 * Base path: /api/v1/customers
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService service;

    // ── FR-CRM-1: Basic CRUD ──────────────────────────────────────────────

    /**
     * Create a new customer record.
     * POST /api/v1/customers
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CustomerResponseDTO> create(
            @Valid @RequestBody CustomerRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    /**
     * Update an existing customer record.
     * PUT /api/v1/customers/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<CustomerResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CustomerRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    /**
     * Get a single customer by ID.
     * GET /api/v1/customers/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<CustomerResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /**
     * Paginated list of all customers (no filters).
     * GET /api/v1/customers
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<CustomerResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * FR-CRM-1: Soft-deactivate a customer (sets status = INACTIVE).
     * Preserves all historical data — does NOT delete the record.
     * PATCH /api/v1/customers/{id}/deactivate
     */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ApiResponse.ok("Customer deactivated", null);
    }

    /**
     * Hard-delete — restricted to ADMIN and OWNER only (SRS 13 Roles & Permissions).
     *
     * <p>A hard delete permanently removes the customer together with their
     * related job cards, invoices and service history. Mid-level roles
     * (MANAGER, SERVICE_ADVISOR) must not be able to destroy that financial
     * and operational history; normal flows should use
     * {@code PATCH /{id}/deactivate}, which preserves all historical records.
     *
     * <p>The endpoint and its service implementation remain available to
     * ADMIN/OWNER as an administrative escape hatch.
     *
     * <p>DELETE /api/v1/customers/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    // ── FR-CRM-6: Search / filter / sort ─────────────────────────────────

    /**
     * Server-side search with optional filters and sort.
     *
     * GET /api/v1/customers/search
     *   ?q=Arjun                — partial match on name, phone, or email
     *   &status=ACTIVE          — filter by status (ACTIVE | INACTIVE)
     *   &vehicleReg=MH-12       — filter by vehicle registration number
     *   &page=0&size=20
     *   &sort=name,asc          — any Customer field
     *
     * vehicleReg takes priority over q when both are supplied.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<CustomerResponseDTO>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String vehicleReg,
            Pageable pageable) {
        return ApiResponse.ok(service.search(q, status, vehicleReg, pageable));
    }

    // ── FR-CRM-2: Vehicles per customer ───────────────────────────────────

    /**
     * All vehicles linked to a customer, newest first.
     * GET /api/v1/customers/{customerId}/vehicles
     */
    @GetMapping("/{customerId}/vehicles")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<List<VehicleResponseDTO>> getVehicles(
            @PathVariable Long customerId) {
        return ApiResponse.ok(service.getVehiclesByCustomer(customerId));
    }

    // ── FR-CRM-3: Service history ─────────────────────────────────────────

    /**
     * Chronological service history for a customer (newest first).
     * GET /api/v1/customers/{customerId}/service-history
     */
    @GetMapping("/{customerId}/service-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<List<ServiceHistoryDTO>> getServiceHistory(
            @PathVariable Long customerId) {
        return ApiResponse.ok(service.getServiceHistoryByCustomer(customerId));
    }

    /**
     * Chronological service history for a vehicle (newest first).
     * GET /api/v1/customers/vehicles/{vehicleId}/service-history
     *
     * Nested under /customers to keep vehicle service-history within the
     * CRM API group without conflicting with /api/v1/vehicles.
     */
    @GetMapping("/vehicles/{vehicleId}/service-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<List<ServiceHistoryDTO>> getVehicleServiceHistory(
            @PathVariable Long vehicleId) {
        return ApiResponse.ok(service.getServiceHistoryByVehicle(vehicleId));
    }

    // ── FR-CRM-7: Customer 360 ────────────────────────────────────────────

    /**
     * Customer 360 composite view.
     * Returns profile + vehicles + service history + appointments +
     * invoices + feedback + communications + summary stats.
     *
     * GET /api/v1/customers/{id}/360
     */
    @GetMapping("/{id}/360")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ApiResponse<Customer360DTO> getCustomer360(@PathVariable Long id) {
        return ApiResponse.ok(service.getCustomer360(id));
    }
}
