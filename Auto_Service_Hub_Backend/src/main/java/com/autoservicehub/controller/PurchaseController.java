package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.PurchaseRequestDTO;
import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.service.PurchaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Purchasing (FR-INV-2)
 * Base path: /api/v1/purchases
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 *
 * <p>Creating a purchase records an order; it does not move stock. Stock moves
 * only through the receive endpoint, which raises IN stock movements through the
 * inventory ledger — the same one the part endpoints use.
 */
@RestController
@RequestMapping("/api/v1/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService service;

    /**
     * Raise a purchase order against a supplier with one or more items.
     * POST /api/v1/purchases
     *
     * <p>Returns 400 for a non-positive quantity or a negative unit price,
     * 404 for an unknown supplier or part. The total is calculated server-side
     * from the lines; any total in the request body is ignored.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseResponseDTO> create(@Valid @RequestBody PurchaseRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<PurchaseResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** GET /api/v1/purchases?page=0&size=20 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PurchaseResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /** Purchase history for one supplier, newest first. */
    @GetMapping("/supplier/{supplierId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PurchaseResponseDTO>> listBySupplier(@PathVariable Long supplierId,
                                                                  Pageable pageable) {
        return ApiResponse.ok(service.listBySupplier(supplierId, pageable));
    }

    /**
     * Receive a purchase: the ordered quantities are added to stock and an IN
     * stock movement is recorded for each line.
     * POST /api/v1/purchases/{id}/receive
     *
     * <p>Returns 404 for an unknown purchase and 409 if the purchase was already
     * received. The whole receipt is one transaction: if any line fails, no part's
     * stock moves, no movement is recorded and the purchase stays PENDING.
     */
    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    public ApiResponse<PurchaseResponseDTO> receive(@PathVariable Long id) {
        return ApiResponse.ok("Received", service.receive(id));
    }
}