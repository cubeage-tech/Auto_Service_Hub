package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.InvoiceLabourItemRequestDTO;
import com.autoservicehub.dto.InvoiceRequestDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.service.InvoiceDocumentService;
import com.autoservicehub.service.InvoiceService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Billing - Invoices (SRS 4.9)
 * Base path: /api/v1/invoices
 * All endpoints require a valid JWT and are further restricted by role (SRS 13).
 */
@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService service;

    /** FR-BILL-4: printable/downloadable invoice documents. */
    private final InvoiceDocumentService documentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponseDTO> create(@Valid @RequestBody InvoiceRequestDTO request) {
        return ApiResponse.ok("Created", service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ApiResponse<InvoiceResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody InvoiceRequestDTO request) {
        return ApiResponse.ok("Updated", service.update(id, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<InvoiceResponseDTO> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<InvoiceResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.list(pageable));
    }

    /**
     * Invoices raised against one job card, newest first.
     * GET /api/v1/invoices/job-card/{jobCardId}?page=0&size=20
     */
    @GetMapping("/job-card/{jobCardId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<Page<InvoiceResponseDTO>> listByJobCard(
            @PathVariable Long jobCardId,
            Pageable pageable) {
        return ApiResponse.ok(service.listByJobCard(jobCardId, pageable));
    }

    /**
     * FR-BILL-4: the invoice as a printable/downloadable PDF.
     * GET /api/v1/invoices/{id}/pdf
     *
     * <p>The role guard is identical to {@link #getById(Long)}, so downloading an
     * invoice is never more permissive than reading one.
     */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        return download(documentService.toPdf(id));
    }

    /**
     * FR-BILL-4: the invoice as a downloadable Excel workbook.
     * GET /api/v1/invoices/{id}/xlsx
     */
    @GetMapping("/{id}/xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ResponseEntity<byte[]> excel(@PathVariable Long id) {
        return download(documentService.toExcel(id));
    }

    /**
     * Streams the rendered file with the right headers. Same contract as the
     * report export: an attachment disposition so a browser never renders it
     * inline, and no-store because the figures are live at request time.
     */
    private ResponseEntity<byte[]> download(ExportFileDTO file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.getContentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFilename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.size());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(file.getContent(), headers, HttpStatus.OK);
    }

    /**
     * Bill a repair task's labour onto this invoice (FR-BILL-2).
     * POST /api/v1/invoices/{id}/labour
     *
     * <p>The line is generated from the task — its description, quantity 1 and
     * {@code JobTask.labourCost} as the unit price — and the invoice's subtotal,
     * GST and total are recalculated server-side. Nothing monetary is accepted
     * in the request.
     *
     * <p>Refused with 409 if the task belongs to a different job card, has
     * already been billed on this invoice, or the invoice is already PAID. 404
     * for an unknown invoice or task.
     */
    @PostMapping("/{id}/labour")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponseDTO> addLabourItem(
            @PathVariable Long id,
            @Valid @RequestBody InvoiceLabourItemRequestDTO request) {
        return ApiResponse.ok("Labour added", service.addLabourItem(id, request));
    }

    /**
     * Amount still owed on an invoice (total minus its successful payments).
     * GET /api/v1/invoices/{id}/outstanding
     */
    @GetMapping("/{id}/outstanding")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<BigDecimal> getOutstandingAmount(@PathVariable Long id) {
        return ApiResponse.ok(service.getOutstandingAmount(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
