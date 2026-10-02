package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import com.autoservicehub.dto.VehicleServiceHistoryDTO;
import com.autoservicehub.service.VehicleService;
import com.autoservicehub.service.impl.VehicleHistoryDocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Vehicle Management (SRS 4.2)
 * Base path: /api/v1/vehicles
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService service;

    /** FR-VEH-6: renders the service history as a downloadable document. */
    private final VehicleHistoryDocumentService vehicleHistoryDocumentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VehicleResponseDTO> create(@Valid @RequestBody VehicleRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    public ApiResponse<VehicleResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody VehicleRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<VehicleResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /**
     * FR-VEH-6: this vehicle's service history, newest visit first.
     * GET /api/v1/vehicles/{id}/service-history
     *
     * <p>A vehicle with no jobs returns an empty history with 200 — the vehicle
     * exists, it has simply never been serviced. Only an unknown id is a 404.
     */
    @GetMapping("/{id}/service-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ApiResponse<VehicleServiceHistoryDTO> serviceHistory(@PathVariable Long id) {
        return ApiResponse.ok(service.getServiceHistory(id));
    }

    /**
     * FR-VEH-6: the same history as a downloadable PDF, rendered through the
     * shared export renderer used by the report and invoice documents.
     * GET /api/v1/vehicles/{id}/service-history.pdf
     *
     * <p>The role guard matches {@link #serviceHistory}, so downloading is never
     * more permissive than reading.
     */
    @GetMapping("/{id}/service-history.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'BILLING_USER')")
    public ResponseEntity<byte[]> serviceHistoryPdf(@PathVariable Long id) {
        ExportFileDTO file = vehicleHistoryDocumentService.toPdf(service.getServiceHistory(id));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.getContentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFilename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.size());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(file.getContent(), headers, HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<VehicleResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
