package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.CustomerGrowthReportDTO;
import com.autoservicehub.dto.DashboardSummaryDTO;
import com.autoservicehub.dto.MechanicPerformanceReportDTO;
import com.autoservicehub.dto.RevenueReportDTO;
import com.autoservicehub.dto.ServiceAdvisorOperationsReportDTO;
import com.autoservicehub.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Reports & Analytics (SRS 9 - Reports API group, 17).
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

    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ApiResponse<RevenueReportDTO> revenue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.getRevenueReport(from, to));
    }

    @GetMapping("/advisor-operations")
    @PreAuthorize("hasRole('SERVICE_ADVISOR')")
    public ApiResponse<ServiceAdvisorOperationsReportDTO> advisorOperations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.getAdvisorOperationsReport(from, to));
    }

    @GetMapping("/mechanic-performance")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MECHANIC')")
    public ApiResponse<MechanicPerformanceReportDTO> mechanicPerformance(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                        @RequestParam(required = false) Long mechanicId) {
        return ApiResponse.ok(reportService.getMechanicPerformanceReport(from, to, mechanicId));
    }

    @GetMapping("/parts-usage")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'INVENTORY_MANAGER')")
    public ApiResponse<Object> partsUsage(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResponse.ok(reportService.getPartsUsageReport(from, to));
    }

    @GetMapping("/customer-growth")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'BILLING_USER')")
    public ApiResponse<CustomerGrowthReportDTO> customerGrowth(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reportService.getCustomerGrowthReport(from, to));
    }

    @GetMapping("/profit-analysis")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'BILLING_USER')")
    public ApiResponse<Object> profitAnalysis(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResponse.ok(reportService.getProfitAnalysisReport(from, to));
    }
}
