package com.autoservicehub.service;

import com.autoservicehub.dto.CustomerGrowthReportDTO;
import com.autoservicehub.dto.DailyWorkshopReportDTO;
import com.autoservicehub.dto.DashboardSummaryDTO;
import com.autoservicehub.dto.MechanicPerformanceReportDTO;
import com.autoservicehub.dto.PartsUsageReportDTO;
import com.autoservicehub.dto.ProfitAnalysisReportDTO;
import com.autoservicehub.dto.ReportFilterDTO;
import com.autoservicehub.dto.RevenuePaymentReportDTO;

import java.util.List;

/**
 * Dashboard &amp; Reports (SRS 4.11, 17).
 *
 * <p>Filters by date range, mechanic, vehicle, service and status per FR-REP-7,
 * carried on {@link ReportFilterDTO}. Export is not handled here (FR-REP-8) and
 * is not implemented yet.
 *
 * <p>Every report is built from stored rows. Where the schema cannot support a
 * metric the report says so explicitly rather than substituting an estimate —
 * see {@link ProfitAnalysisReportDTO}, which is the clearest case.
 */
public interface ReportService {

    DashboardSummaryDTO getDashboardSummary();

    /** FR-REP-1: jobs assigned on a day, split completed vs open, with a status breakdown. */
    DailyWorkshopReportDTO getDailyWorkshopReport(ReportFilterDTO filter);

    /** FR-REP-4: invoiced revenue alongside successful payments actually collected. */
    RevenuePaymentReportDTO getRevenuePaymentReport(ReportFilterDTO filter);

    /** FR-REP-2: one entry per mechanic, ordered by job volume. */
    List<MechanicPerformanceReportDTO> getMechanicPerformanceReport(ReportFilterDTO filter);

    /** FR-REP-3: parts consumed, counting OUT stock movements only. */
    PartsUsageReportDTO getPartsUsageReport(ReportFilterDTO filter);

    /** FR-REP-5: new customers and returning customers over the window. */
    CustomerGrowthReportDTO getCustomerGrowthReport(ReportFilterDTO filter);

    /** FR-REP-4: revenue and the cost that genuinely exists, plus stated limits. */
    ProfitAnalysisReportDTO getProfitAnalysisReport(ReportFilterDTO filter);
}
