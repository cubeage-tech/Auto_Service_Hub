package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.PurchaseResponseDTO;
import com.autoservicehub.dto.SupplierRequestDTO;
import com.autoservicehub.dto.SupplierResponseDTO;
import com.autoservicehub.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Supplier Management (FR-INV-2)
 * Base path: /api/v1/suppliers
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 *
 * <p>Write access follows the inventory roles already used by
 * {@code PartController}, so purchasing and stock are governed by the same
 * roles; reads are open to the wider workshop staff that needs supplier
 * contact details.
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierResponseDTO> create(@Valid @RequestBody SupplierRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    public ApiResponse<SupplierResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody SupplierRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<SupplierResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** GET /api/v1/suppliers?page=0&size=20 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<SupplierResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Search suppliers by free text.
     * GET /api/v1/suppliers/search?q=bharat&page=0&size=20
     *
     * <p>Matches name, phone, email and address, so a user can find a supplier by
     * whichever detail they happen to know. An absent or blank {@code q} returns
     * the full list, so clearing a search box shows everything rather than an
     * error.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<SupplierResponseDTO>> search(
            @RequestParam(required = false) String q, Pageable pageable) {
        return ApiResponse.ok(service.search(q, pageable));
    }

    /**
     * This supplier's purchase orders, newest first.
     * GET /api/v1/suppliers/{id}/purchases?page=0&size=20
     *
     * <p>Purchase history reached from the supplier itself. Same data as
     * {@code GET /api/v1/purchases/supplier/{supplierId}}, offered on both
     * resources so a caller can start from whichever one it is already holding.
     */
    @GetMapping("/{id}/purchases")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PurchaseResponseDTO>> purchaseHistory(
            @PathVariable Long id, Pageable pageable) {
        return ApiResponse.ok(service.purchaseHistory(id, pageable));
    }

    /**
     * Remove a supplier that has no purchase history.
     * DELETE /api/v1/suppliers/{id}
     *
     * <p>Returns 409 if the supplier has been ordered from — its name is
     * referenced by those purchases and cannot simply be removed.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}