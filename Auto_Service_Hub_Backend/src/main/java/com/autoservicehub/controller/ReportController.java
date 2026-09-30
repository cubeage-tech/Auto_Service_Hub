package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.CustomerGrowthReportDTO;
import com.autoservicehub.dto.DailyWorkshopReportDTO;
import com.autoservicehub.dto.DashboardSummaryDTO;
import com.autoservicehub.dto.MechanicPerformanceReportDTO;
import com.autoservicehub.dto.PartsUsageReportDTO;
import com.autoservicehub.dto.ProfitAnalysisReportDTO;
import com.autoservicehub.dto.ReportFilterDTO;
import com.autoservicehub.dto.RevenuePaymentReportDTO;
import com.autoservicehub.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Reports &amp; Analytics (SRS 9 - Reports API group, 17; FR-REP-1..8).
 * Base path: /api/v1/reports
 *
 * <p>Filters (FR-REP-7) are supplied per request and only where the repository
 * layer can honour them: {@code from}/{@code to}, {@code mechanicId},
 * {@code vehicleId}, {@code serviceType}, {@code status}, plus {@code date} for
 * the single-day workshop report and {@code jobCardId} for parts usage.
 *
 * <p>There is deliberately no filter the database could not back — no amount
 * range, no free-text job search, no rating threshold.
 *
 * <p>A reversed or missing range is rejected by the service as a
 * {@code BusinessRuleException} → 409, through the project's existing handler.
 *
 * <p>PDF/Excel export (FR-REP-8) and the AI Insights endpoint are NOT implemented
 * yet.
 *
 * <p>Role guards follow the existing convention: revenue, profit and growth are
 * management/billing data; parts usage is visible to inventory; mechanic
 * performance is visible to the mechanic themselves.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')")
    public ApiResponse<DashboardSummaryDTO> dashboard() {
        return ApiResponse.ok(reportService.getDashboardSummary());
    }

    /** FR-REP-1: jobs on one day (defaults to today), with the status breakdown. */
    @GetMapping("/daily-workshop")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<DailyWorkshopReportDTO> dailyWorkshop(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String serviceType,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reportService.getDailyWorkshopReport(
                filter(date, null, null, mechanicId, vehicleId, serviceType, status, null)));
    }

    /** FR-REP-4: invoiced revenue alongside payments actually collected. */
    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ApiResponse<RevenuePaymentReportDTO> revenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reportService.getRevenuePaymentReport(
                filter(null, from, to, mechanicId, vehicleId, null, status, null)));
    }

    /** FR-REP-2: one entry per mechanic. */
    @GetMapping("/mechanic-performance")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC')")
    public ApiResponse<List<MechanicPerformanceReportDTO>> mechanicPerformance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId) {
        return ApiResponse.ok(reportService.getMechanicPerformanceReport(
                filter(null, from, to, mechanicId, null, null, null, null)));
    }

    /** FR-REP-3: parts consumed, counting OUT movements only. */
    @GetMapping("/parts-usage")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'INVENTORY_MANAGER')")
    public ApiResponse<PartsUsageReportDTO> partsUsage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long mechanicId,
            @RequestParam(required = false) Long jobCardId) {
        return ApiResponse.ok(reportService.getPartsUsageReport(
                filter(null, from, to, mechanicId, null, null, null, jobCardId)));
    }

    /** FR-REP-5: new customers and returning customers. */
    @GetMapping("/customer-growth")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ApiResponse<CustomerGrowthReportDTO> customerGrowth(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.getCustomerGrowthReport(
                filter(null, from, to, null, null, null, null, null)));
    }

    /**
     * FR-REP-4: revenue, the cost that exists, and the stated limits.
     *
     * <p>Returns {@code profitAvailable = false} and a null {@code grossProfit}
     * rather than a fabricated margin — see {@link ProfitAnalysisReportDTO}.
     */
    @GetMapping("/profit-analysis")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ApiResponse<ProfitAnalysisReportDTO> profitAnalysis(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.getProfitAnalysisReport(
                filter(null, from, to, null, null, null, null, null)));
    }

    /**
     * Collects the supported query parameters into one filter object.
     *
     * <p>A null simply means "do not filter on this": the repository queries treat
     * a null filter as absent, not as a match-nothing value.
     */
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
