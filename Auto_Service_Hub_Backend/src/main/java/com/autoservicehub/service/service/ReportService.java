package com.autoservicehub.service;

import com.autoservicehub.dto.CustomerGrowthReportDTO;
import com.autoservicehub.dto.DashboardSummaryDTO;
import com.autoservicehub.dto.MechanicPerformanceReportDTO;
import com.autoservicehub.dto.RevenueReportDTO;
import com.autoservicehub.dto.ServiceAdvisorOperationsReportDTO;
import java.time.LocalDate;

/**
 * Dashboard & Reports (SRS 4.11, 17). Filters by date range, mechanic, vehicle,
 * service and status per FR-REP-7; export handled at the controller layer (FR-REP-8).
 */
public interface ReportService {
    DashboardSummaryDTO getDashboardSummary();
    RevenueReportDTO getRevenueReport(LocalDate from, LocalDate to);
    ServiceAdvisorOperationsReportDTO getAdvisorOperationsReport(LocalDate from, LocalDate to);
    MechanicPerformanceReportDTO getMechanicPerformanceReport(LocalDate from, LocalDate to, Long mechanicId);
    Object getPartsUsageReport(LocalDate from, LocalDate to);
    CustomerGrowthReportDTO getCustomerGrowthReport(LocalDate from, LocalDate to);
    Object getProfitAnalysisReport(LocalDate from, LocalDate to);
}
