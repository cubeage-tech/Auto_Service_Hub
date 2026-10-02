package com.autoservicehub.controller;

import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.ExportReportType;
import com.autoservicehub.dto.ReportFilterDTO;
import com.autoservicehub.service.ReportExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * PDF / Excel export for the reports module (SRS FR-REP-8).
 * Base path: /api/v1/reports/export
 *
 * <p>One endpoint per report per format, e.g.
 * {@code GET /api/v1/reports/export/daily-workshop.pdf} and
 * {@code .../daily-workshop.xlsx}. Using the extension in the path keeps a single
 * mapping per report rather than a {@code ?format=} switch, and makes the
 * download obvious in a browser's address bar and in logs.
 *
 * <p>Accepts exactly the filters its JSON counterpart accepts, and passes them
 * straight through — so an export can never be narrower than the report it
 * mirrors. Role guards match the JSON endpoints exactly, so exporting is never
 * more permissive than reading.
 *
 * <p>An unknown report slug yields 404; a bad or missing filter behaves exactly
 * as it does on the JSON endpoint, because the same service call raises it.
 */
@RestController
@RequestMapping("/api/v1/reports/export")
@RequiredArgsConstructor
public class ReportExportController {

    private final ReportExportService exportService;

    /** FR-REP-1: daily workshop, as PDF. */
    @GetMapping("/daily-workshop.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ResponseEntity<byte[]> dailyWorkshopPdf(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String serviceType,
            @RequestParam(required = false) String status) {
        return download(exportService.exportPdf(ExportReportType.DAILY_WORKSHOP,
                filter(date, null, null, mechanicId, vehicleId, serviceType, status, null)));
    }

    /** FR-REP-1: daily workshop, as Excel. */
    @GetMapping("/daily-workshop.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ResponseEntity<byte[]> dailyWorkshopExcel(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String serviceType,
            @RequestParam(required = false) String status) {
        return download(exportService.exportExcel(ExportReportType.DAILY_WORKSHOP,
                filter(date, null, null, mechanicId, vehicleId, serviceType, status, null)));
    }

    /** FR-REP-4: revenue and payments, as PDF. */
    @GetMapping("/revenue-payment.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ResponseEntity<byte[]> revenuePdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String status) {
        return download(exportService.exportPdf(ExportReportType.REVENUE_PAYMENT,
                filter(null, from, to, mechanicId, vehicleId, null, status, null)));
    }

    /** FR-REP-4: revenue and payments, as Excel. */
    @GetMapping("/revenue-payment.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ResponseEntity<byte[]> revenueExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String status) {
        return download(exportService.exportExcel(ExportReportType.REVENUE_PAYMENT,
                filter(null, from, to, mechanicId, vehicleId, null, status, null)));
    }

    /** FR-REP-2: mechanic performance, as PDF. */
    @GetMapping("/mechanic-performance.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ResponseEntity<byte[]> mechanicPerformancePdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId) {
        return download(exportService.exportPdf(ExportReportType.MECHANIC_PERFORMANCE,
                filter(null, from, to, mechanicId, null, null, null, null)));
    }

    /** FR-REP-2: mechanic performance, as Excel. */
    @GetMapping("/mechanic-performance.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ResponseEntity<byte[]> mechanicPerformanceExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId) {
        return download(exportService.exportExcel(ExportReportType.MECHANIC_PERFORMANCE,
                filter(null, from, to, mechanicId, null, null, null, null)));
    }

    /** FR-REP-3: parts usage, as PDF. */
    @GetMapping("/parts-usage.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'INVENTORY_MANAGER')")
    public ResponseEntity<byte[]> partsUsagePdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long jobCardId) {
        return download(exportService.exportPdf(ExportReportType.PARTS_USAGE,
                filter(null, from, to, mechanicId, null, null, null, jobCardId)));
    }

    /** FR-REP-3: parts usage, as Excel. */
    @GetMapping("/parts-usage.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'INVENTORY_MANAGER')")
    public ResponseEntity<byte[]> partsUsageExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long jobCardId) {
        return download(exportService.exportExcel(ExportReportType.PARTS_USAGE,
                filter(null, from, to, mechanicId, null, null, null, jobCardId)));
    }

    /** FR-REP-5: customer growth, as PDF. */
    @GetMapping("/customer-growth.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ResponseEntity<byte[]> customerGrowthPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(exportService.exportPdf(ExportReportType.CUSTOMER_GROWTH,
                filter(null, from, to, null, null, null, null, null)));
    }

    /** FR-REP-5: customer growth, as Excel. */
    @GetMapping("/customer-growth.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ResponseEntity<byte[]> customerGrowthExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(exportService.exportExcel(ExportReportType.CUSTOMER_GROWTH,
                filter(null, from, to, null, null, null, null, null)));
    }

    /** FR-REP-4: profit analysis, as PDF. */
    @GetMapping("/profit-analysis.pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ResponseEntity<byte[]> profitAnalysisPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(exportService.exportPdf(ExportReportType.PROFIT_ANALYSIS,
                filter(null, from, to, null, null, null, null, null)));
    }

    /** FR-REP-4: profit analysis, as Excel. */
    @GetMapping("/profit-analysis.xlsx")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ResponseEntity<byte[]> profitAnalysisExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(exportService.exportExcel(ExportReportType.PROFIT_ANALYSIS,
                filter(null, from, to, null, null, null, null, null)));
    }

    /**
     * Streams the rendered file with the right headers.
     *
     * <p>{@code Content-Disposition: attachment} stops a browser rendering the
     * payload inline, and {@code filename*=UTF-8''…} carries the name in a form
     * browsers and proxies handle correctly — a bare {@code filename=} is ambiguous
     * for non-ASCII names.
     */
    private ResponseEntity<byte[]> download(ExportFileDTO file) {
        MediaType mediaType = MediaType.parseMediaType(file.getContentType());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFilename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.size());
        // Reports are generated per request from live data; caching one would
        // serve stale figures that no longer match the database.
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(file.getContent(), headers, org.springframework.http.HttpStatus.OK);
    }

    /** Collects the supported query parameters, mirroring ReportController exactly. */
    private ReportFilterDTO filter(LocalDate date, LocalDate from, LocalDate to,
                                   Long mechanicId, Long vehicleId, String serviceType,
                                   String status, Long jobCardId) {
        ReportFilterDTO f = new ReportFilterDTO();
        f.setDate(date);
        f.setFrom(from);
        f.setTo(to);
        f.setMechanicId(mechanicId);
        f.setVehicleId(vehicleId);
        f.setServiceType(serviceType);
        f.setStatus(status);
        f.setJobCardId(jobCardId);
        return f;
    }
}
