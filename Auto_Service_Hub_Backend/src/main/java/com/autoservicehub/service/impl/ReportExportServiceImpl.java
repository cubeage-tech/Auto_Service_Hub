package com.autoservicehub.service.impl;

import com.autoservicehub.dto.*;
import com.autoservicehub.service.ReportExportService;
import com.autoservicehub.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * PDF and Excel rendering for the reports module (FR-REP-8).
 *
 * <p>The flow is deliberately one-way and single-source:
 *
 * <pre>
 *   filters -> ReportService (the JSON path) -> ExportDocumentDTO -> PDF | Excel
 * </pre>
 *
 * <p>No total, rate or count is recomputed here. {@code buildDocument} only
 * reshapes values the report already computed into labelled rows, so an export
 * cannot disagree with its JSON counterpart. Formatting decisions — how a null
 * prints, how a rate is scaled — are made once, in {@link #cell}, so both output
 * formats render identically too.
 */
@Service
@RequiredArgsConstructor
public class ReportExportServiceImpl implements ReportExportService {

    public static final String CONTENT_TYPE_PDF   = ExportRenderer.CONTENT_TYPE_PDF;
    public static final String CONTENT_TYPE_EXCEL = ExportRenderer.CONTENT_TYPE_EXCEL;

    private static final DateTimeFormatter STAMP = ExportRenderer.STAMP;

    /** How a null is written. Blank rather than "null" — absence is not a value. */
    private static final String BLANK = ExportRenderer.BLANK;

    private final ReportService reportService;

    /**
     * Rendering lives in {@link ExportRenderer} so the invoice document of
     * FR-BILL-4 is produced by the same code as these report exports.
     */
    private final ExportRenderer renderer;

    @Override
    public ExportFileDTO exportPdf(ExportReportType type, ReportFilterDTO filter) {
        ExportDocumentDTO doc = buildDocument(type, filter);
        return new ExportFileDTO(renderer.renderPdf(doc), CONTENT_TYPE_PDF, filename(type, "pdf"));
    }

    @Override
    public ExportFileDTO exportExcel(ExportReportType type, ReportFilterDTO filter) {
        ExportDocumentDTO doc = buildDocument(type, filter);
        return new ExportFileDTO(renderer.renderExcel(doc), CONTENT_TYPE_EXCEL, filename(type, "xlsx"));
    }

    // â”€â”€ Document assembly: calls ReportService, invents nothing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Fetches the report through {@link ReportService} and reshapes it.
     *
     * <p>The service call is the JSON endpoint's own call, so any validation it
     * performs (reversed range, missing dates) propagates unchanged to an export.
     */
    private ExportDocumentDTO buildDocument(ExportReportType type, ReportFilterDTO filter) {
        ReportFilterDTO f = filter == null ? new ReportFilterDTO() : filter;
        ExportDocumentDTO doc = new ExportDocumentDTO(type.getTitle(), type.getRequirement(),
                describeFilters(f), LocalDateTime.now());

        switch (type) {
            case DAILY_WORKSHOP -> addDailyWorkshop(doc, reportService.getDailyWorkshopReport(f));
            case REVENUE_PAYMENT -> addRevenuePayment(doc, reportService.getRevenuePaymentReport(f));
            case MECHANIC_PERFORMANCE ->
                    addMechanicPerformance(doc, reportService.getMechanicPerformanceReport(f));
            case PARTS_USAGE -> addPartsUsage(doc, reportService.getPartsUsageReport(f));
            case CUSTOMER_GROWTH -> addCustomerGrowth(doc, reportService.getCustomerGrowthReport(f));
            case PROFIT_ANALYSIS -> addProfitAnalysis(doc, reportService.getProfitAnalysisReport(f));
        }

        if (doc.totalRowCount() == 0) {
            doc.addNote("No data matched the applied filters. The report is empty, not failed.");
        }
        return doc;
    }

    private void addDailyWorkshop(ExportDocumentDTO doc, DailyWorkshopReportDTO r) {
        doc.addTable(new ExportTableDTO("Summary", List.of("Metric", "Value"))
                .addRow("Date", cell(r.getDate()))
                .addRow("Total jobs", cell(r.getTotalJobs()))
                .addRow("Completed jobs", cell(r.getCompletedJobs()))
                .addRow("Pending / in-progress jobs", cell(r.getPendingJobs()))
                .addRow("Invoiced amount", cell(r.getInvoicedAmount()))
                .addRow("Collected amount", cell(r.getCollectedAmount())));

        ExportTableDTO breakdown =
                new ExportTableDTO("Status breakdown", List.of("Status", "Jobs"));
        // Insertion order is preserved, so the export matches the report's order.
        for (Map.Entry<String, Long> e : r.getStatusBreakdown().entrySet()) {
            breakdown.addRow(e.getKey(), cell(e.getValue()));
        }
        doc.addTable(breakdown);
        doc.addNote(r.getLimitation());
    }

    private void addRevenuePayment(ExportDocumentDTO doc, RevenuePaymentReportDTO r) {
        doc.addTable(new ExportTableDTO("Summary", List.of("Metric", "Value"))
                .addRow("Period", cell(r.getFrom()) + " to " + cell(r.getTo()))
                .addRow("Invoiced revenue", cell(r.getInvoicedRevenue()))
                .addRow("Invoice count", cell(r.getInvoiceCount()))
                .addRow("Average invoice value", cell(r.getAverageInvoiceValue()))
                .addRow("Collected amount (SUCCESS payments)", cell(r.getCollectedAmount()))
                .addRow("Payment count", cell(r.getPaymentCount()))
                .addRow("Average payment value", cell(r.getAveragePaymentValue()))
                .addRow("Outstanding (invoiced less collected)", cell(r.getOutstandingAmount())));

        ExportTableDTO modes =
                new ExportTableDTO("Payments by mode", List.of("Mode", "Count", "Amount"));
        for (PaymentModeSummaryDTO m : r.getPaymentModes()) {
            modes.addRow(m.getMode(), cell(m.getPaymentCount()), cell(m.getTotal()));
        }
        doc.addTable(modes);

        ExportTableDTO statuses =
                new ExportTableDTO("Invoices by status", List.of("Status", "Count", "Total"));
        for (InvoiceStatusSummaryDTO s : r.getInvoiceStatuses()) {
            statuses.addRow(s.getStatus(), cell(s.getInvoiceCount()), cell(s.getTotal()));
        }
        doc.addTable(statuses);

        ExportTableDTO trend =
                new ExportTableDTO("Revenue by day", List.of("Date", "Invoices", "Total"));
        for (DailyRevenueSummaryDTO d : r.getDailyTrend()) {
            trend.addRow(cell(d.getDate()), cell(d.getInvoiceCount()), cell(d.getTotal()));
        }
        doc.addTable(trend);
    }

    private void addMechanicPerformance(ExportDocumentDTO doc,
                                         List<MechanicPerformanceReportDTO> rows) {
        ExportTableDTO t = new ExportTableDTO("Per mechanic",
                List.of("Mechanic", "Employee code", "Assigned", "Completed", "Open",
                        "Completion rate", "Revenue", "Avg revenue/job", "Avg rating", "Ratings"));
        for (MechanicPerformanceReportDTO m : rows) {
            t.addRow(m.getMechanicName(), m.getEmployeeCode(),
                    cell(m.getAssignedJobs()), cell(m.getCompletedJobs()), cell(m.getOpenJobs()),
                    cell(m.getCompletionRate()), cell(m.getTotalRevenue()),
                    cell(m.getAverageRevenuePerJob()),
                    // Null rating stays null: "not rated" is not "rated zero".
                    m.getAverageCustomerRating() == null ? BLANK : cell(m.getAverageCustomerRating()),
                    cell(m.getRatingCount()));
        }
        doc.addTable(t);

        String unsupported = rows.isEmpty() ? null : rows.get(0).getUnsupportedMetrics();
        doc.addNote(unsupported);
    }

    private void addPartsUsage(ExportDocumentDTO doc, PartsUsageReportDTO r) {
        ExportTableDTO totals = new ExportTableDTO("Summary", List.of("Metric", "Value"))
                .addRow("Period", cell(r.getFrom()) + " to " + cell(r.getTo()))
                .addRow("Distinct parts", cell(r.getDistinctPartCount()))
                .addRow("Total quantity consumed", cell(r.getTotalQuantityConsumed()))
                .addRow("Total estimated cost", cell(r.getTotalEstimatedCost()));

        ExportTableDTO parts = new ExportTableDTO("Parts consumed",
                List.of("SKU", "Part", "Unit", "Quantity", "Movements",
                        "Current purchase price", "Estimated cost"));
        for (PartsUsageRowDTO p : r.getParts()) {
            parts.addRow(p.getSku(), p.getPartName(), p.getUnit(),
                    cell(p.getQuantityConsumed()), cell(p.getMovementCount()),
                    // A part with no recorded price shows blank, never 0.00,
                    // which would read as "free" rather than "unknown".
                    p.getCurrentPurchasePrice() == null ? BLANK : cell(p.getCurrentPurchasePrice()),
                    cell(p.getEstimatedCost()));
        }

        doc.addTable(totals).addTable(parts);
        doc.addNote(r.getMethodology());
    }

    private void addCustomerGrowth(ExportDocumentDTO doc, CustomerGrowthReportDTO r) {
        doc.addTable(new ExportTableDTO("Summary", List.of("Metric", "Value"))
                .addRow("Period", cell(r.getFrom()) + " to " + cell(r.getTo()))
                .addRow("New customers", cell(r.getNewCustomerCount()))
                .addRow("Total customers at end of period", cell(r.getTotalCustomersAtEndOfPeriod()))
                .addRow("Repeat customers", cell(r.getRepeatCustomerCount()))
                .addRow("Customers served in period", cell(r.getCustomersServedInPeriod()))
                .addRow("Jobs in period", cell(r.getJobsInPeriod()))
                .addRow("Repeat rate", r.getRepeatRate() == null ? BLANK : cell(r.getRepeatRate())));
        doc.addNote(r.getNotes());
    }

    private void addProfitAnalysis(ExportDocumentDTO doc, ProfitAnalysisReportDTO r) {
        doc.addTable(new ExportTableDTO("Revenue", List.of("Metric", "Value"))
                .addRow("Period", cell(r.getFrom()) + " to " + cell(r.getTo()))
                .addRow("Invoiced revenue", cell(r.getInvoicedRevenue()))
                .addRow("Invoice count", cell(r.getInvoiceCount()))
                .addRow("Collected amount", cell(r.getCollectedAmount()))
                .addRow("Payment count", cell(r.getPaymentCount()))
                .addRow("Outstanding", cell(r.getOutstandingAmount())));

        doc.addTable(new ExportTableDTO("Cost", List.of("Metric", "Value"))
                .addRow("Estimated parts cost (OUT movements)", cell(r.getPartsCostEstimate()))
                .addRow("Parts consumed quantity", cell(r.getPartsConsumedQuantity())));

        // The conclusion is stated in the export itself, so a downloaded file
        // cannot be read as a profit margin when none was calculated.
        doc.addTable(new ExportTableDTO("Conclusion", List.of("Metric", "Value"))
                .addRow("Profit available", r.isProfitAvailable() ? "yes" : "no")
                .addRow("Gross profit", r.getGrossProfit() == null
                        ? "NOT CALCULATED — see limitations" : cell(r.getGrossProfit())));

        ExportTableDTO limits =
                new ExportTableDTO("Limitations", List.of("#", "Limitation"));
        int i = 1;
        for (String limitation : r.getLimitations()) {
            limits.addRow(cell(i++), limitation);
        }
        doc.addTable(limits);
    }
    // â”€â”€ Shared formatting â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Renders one value for display. Delegates to {@link ExportRenderer} so the
     * report exports and the invoice document format a null identically.
     */
    private String cell(Object value) {
        return ExportRenderer.cell(value);
    }

    /**
     * Describes the filters actually applied, for the document header.
     *
     * <p>A filter the report supports is never dropped silently: an unset filter
     * is written as "all" so a reader can tell "no filter" apart from "a filter
     * that was quietly discarded".
     */
    private String describeFilters(ReportFilterDTO f) {
        StringBuilder sb = new StringBuilder();
        sb.append("date=").append(f.getDate() == null ? "today" : f.getDate());
        sb.append("; from=").append(f.getFrom() == null ? "-" : f.getFrom());
        sb.append("; to=").append(f.getTo() == null ? "-" : f.getTo());
        sb.append("; mechanicId=").append(f.getMechanicId() == null ? "all" : f.getMechanicId());
        sb.append("; vehicleId=").append(f.getVehicleId() == null ? "all" : f.getVehicleId());
        sb.append("; serviceType=").append(f.getServiceType() == null ? "all" : f.getServiceType());
        sb.append("; status=").append(f.getStatus() == null ? "all" : f.getStatus());
        sb.append("; jobCardId=").append(f.getJobCardId() == null ? "all" : f.getJobCardId());
        return sb.toString();
    }

    /**
     * Builds a safe download filename, e.g. {@code daily-workshop-2026-03-10.pdf}.
     *
     * <p>The report slug is a fixed enum value, so the only caller-controlled part
     * is the date; it is validated and never allowed to contain a path
     * separator, which keeps the header safe from header/filename injection.
     */
    private String filename(ExportReportType type, String extension) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return type.getSlug() + "-" + stamp + "." + extension;
    }
}
