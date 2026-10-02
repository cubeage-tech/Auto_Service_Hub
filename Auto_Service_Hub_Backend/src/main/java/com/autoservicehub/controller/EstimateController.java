package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.service.EstimateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Billing - Estimates (SRS 4.9)
 * Base path: /api/v1/estimates
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/estimates")
@RequiredArgsConstructor
public class EstimateController {

    private final EstimateService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EstimateResponseDTO> create(@Valid @RequestBody EstimateRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ApiResponse<EstimateResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody EstimateRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<EstimateResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<EstimateResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Estimates raised against one job card, newest first.
     * GET /api/v1/estimates/job-card/{jobCardId}?page=0&size=20
     */
    @GetMapping("/job-card/{jobCardId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<EstimateResponseDTO>> listByJobCard(
            @PathVariable Long jobCardId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByJobCard(jobCardId, pageable));
    }

    /**
 * Turn an accepted estimate into an invoice (FR-BILL-1 → FR-BILL-2).
 * POST /api/v1/estimates/{id}/convert
 *
 * <p>Nested under the estimate, matching the project's convention for an action
 * on a resource ({@code POST /api/v1/purchases/{id}/receive},
 * {@code POST /api/v1/job-cards/{id}/parts}). There is no request body: the
 * lines, prices and discount all come from the estimate, so anything the client
 * sent would only be a chance to disagree with the quote.
 *
 * <p>Returns the resulting invoice. 404 for an unknown estimate; 409 if it has
 * already been converted, has no lines, or one of its lines cannot be billed.
 * The estimate is marked CONVERTED, so it can no longer be edited or converted
 * again.
 */
@PostMapping("/{id}/convert")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<InvoiceResponseDTO> convert(@PathVariable Long id) {
    return ApiResponse.ok("Converted to invoice", service.convertToInvoice(id));
}

@DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
