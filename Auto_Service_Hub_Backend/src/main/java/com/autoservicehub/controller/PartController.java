package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.PartRequestDTO;
import com.autoservicehub.dto.PartResponseDTO;
import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.dto.StockMovementResponseDTO;
import com.autoservicehub.service.PartService;
import com.autoservicehub.service.StockMovementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Spare Parts Inventory (SRS 4.7)
 * Base path: /api/v1/parts
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 *
 * <p>Stock on hand is never edited directly through the part update endpoint —
 * it moves only via a stock movement (IN / OUT / ADJUSTMENT), so every change
 * is recorded in the ledger.
 */
@RestController
@RequestMapping("/api/v1/parts")
@RequiredArgsConstructor
public class PartController {

    private final PartService          service;
    private final StockMovementService stockMovementService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PartResponseDTO> create(@Valid @RequestBody PartRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    public ApiResponse<PartResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody PartRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<PartResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PartResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Parts at or below their own reorder level, worst first.
     * GET /api/v1/parts/low-stock?page=0&size=20
     */
    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<PartResponseDTO>> listLowStock(Pageable pageable) {
        return ApiResponse.ok(service.listLowStock(pageable));
    }

    /**
     * Record a stock movement and apply it to the part's stock.
     * POST /api/v1/parts/{id}/stock-movements
     *
     * <p>IN increases stock, OUT decreases it, ADJUSTMENT applies a signed
     * delta. Stock can never be driven below zero — an insufficient-stock
     * request is rejected with 409 and nothing is written.
     */
    @PostMapping("/{id}/stock-movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockMovementResponseDTO> createStockMovement(
            @PathVariable Long id,
            @Valid @RequestBody StockMovementRequestDTO request) {
        // The path id is authoritative; ignore any partId in the body.
        request.setPartId(id);
        return ApiResponse.ok("Created", stockMovementService.create(request));
    }

    /**
     * Stock movement history for one part, newest first.
     * GET /api/v1/parts/{id}/stock-movements?page=0&size=20
     */
    @GetMapping("/{id}/stock-movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<StockMovementResponseDTO>> listStockMovements(
            @PathVariable Long id,
            Pageable pageable) {
        return ApiResponse.ok(stockMovementService.listByPart(id, pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
