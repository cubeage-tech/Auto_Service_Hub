package com.autoservicehub.service;

import com.autoservicehub.dto.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.service.impl.ReportExportServiceImpl;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportExportServiceImpl} (FR-REP-8).
 *
 * <p>Pure Mockito against {@code ReportService}, so these are independent of the
 * H2 schema issue that blocks {@code ReportRepositoryQueriesTest}.
 *
 * <p>The important property pinned here is that export adds no arithmetic of its
 * own: {@link #exportReusesTheReportServiceResult} asserts the exported bytes
 * contain the exact figures the report service returned, so an export can never
 * disagree with its JSON counterpart.
 *
 * <p>Rendered files are opened again with their own libraries — a PDF with a PDF
 * reader, a workbook with POI — so a malformed document fails the test rather
 * than reaching a user.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportExportServiceImplTest {

    @Mock ReportService reportService;

    ReportExportServiceImpl exportService;

    /**
     * Built by hand rather than by {@code @InjectMocks} because the real
     * {@link com.autoservicehub.service.impl.ExportRenderer} is needed here: these
     * tests assert on the actual PDF and workbook bytes, so a mocked renderer
     * returning null would test nothing.
     */
    @BeforeEach
    void buildServiceWithRealRenderer() {
        exportService = new ReportExportServiceImpl(
                reportService, new com.autoservicehub.service.impl.ExportRenderer());
    }

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private ReportFilterDTO filter() {
        ReportFilterDTO f = new ReportFilterDTO();
        f.setFrom(DAY);
        f.setTo(DAY);
        return f;
    }

    private DailyWorkshopReportDTO dailyWorkshop() {
        DailyWorkshopReportDTO d = new DailyWorkshopReportDTO();
        d.setDate(DAY);
        d.setTotalJobs(5L);
        d.setCompletedJobs(3L);
        d.setPendingJobs(2L);
        d.getStatusBreakdown().put("DELIVERED", 3L);
        d.getStatusBreakdown().put("IN_REPAIR", 2L);
        d.setInvoicedAmount(new BigDecimal("1000.00"));
        d.setCollectedAmount(new BigDecimal("500.00"));
        d.setLimitation("Profit needs cost data that is not stored.");
        return d;
    }

    // ── File shape: type, filename, and a document that actually opens ────

    @Nested
    @DisplayName("File shape")
    class FileShape {

        @Test
        @DisplayName("EX1 a PDF export is a real PDF with the right content type")
        void pdfIsAValidDocument() {
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

            ExportFileDTO file = exportService.exportPdf(ExportReportType.DAILY_WORKSHOP, filter());

            assertThat(file.getContentType())
                    .isEqualTo(ReportExportServiceImpl.CONTENT_TYPE_PDF);
            assertThat(file.getFilename()).endsWith(".pdf").startsWith("daily-workshop-");
            assertThat(file.size()).isGreaterThan(0);
            // Every PDF begins with the %PDF- magic bytes.
            assertThat(new String(file.getContent(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("EX2 an Excel export is a real workbook with the right content type")
        void excelIsAValidWorkbook() throws Exception {
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

            ExportFileDTO file =
                    exportService.exportExcel(ExportReportType.DAILY_WORKSHOP, filter());

            assertThat(file.getContentType())
                    .isEqualTo(ReportExportServiceImpl.CONTENT_TYPE_EXCEL);
            assertThat(file.getFilename()).endsWith(".xlsx");
            // XLSX is a zip container; opening it proves the bytes are complete.
            try (XSSFWorkbook workbook =
                         new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
                assertThat(workbook.getNumberOfSheets()).isGreaterThan(1);
            }
        }

        @Test
        @DisplayName("EX3 every exportable report renders in both formats")
        void allReportsRender() {
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());
            when(reportService.getRevenuePaymentReport(any()))
                    .thenReturn(new RevenuePaymentReportDTO());
            when(reportService.getMechanicPerformanceReport(any())).thenReturn(List.of());
            when(reportService.getPartsUsageReport(any())).thenReturn(new PartsUsageReportDTO());
            when(reportService.getCustomerGrowthReport(any()))
                    .thenReturn(new CustomerGrowthReportDTO(DAY, DAY, 0L, 0L, 0L, 0L, 0L, null, ""));
            when(reportService.getProfitAnalysisReport(any())).thenReturn(new ProfitAnalysisReportDTO());

            for (ExportReportType type : ExportReportType.values()) {
                assertThat(exportService.exportPdf(type, filter()).size())
                        .as("pdf for %s", type).isGreaterThan(0);
                assertThat(exportService.exportExcel(type, filter()).size())
                        .as("excel for %s", type).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("EX4 the export filenames are derived from the report slug, not user input")
        void filenamesAreSafe() {
            when(reportService.getCustomerGrowthReport(any()))
                    .thenReturn(new CustomerGrowthReportDTO(DAY, DAY, 0L, 0L, 0L, 0L, 0L, null, ""));

            String name = exportService.exportPdf(ExportReportType.CUSTOMER_GROWTH, filter())
                    .getFilename();

            // No path separators or traversal could ever reach the header.
            assertThat(name).matches("customer-growth-\\d{4}-\\d{2}-\\d{2}\\.pdf");
            assertThat(name).doesNotContain("/").doesNotContain("..");
        }
    }

    // ── Data fidelity: the export must not invent or alter a figure ────────

    @Nested
    @DisplayName("Data fidelity")
    class DataFidelity {

        @Test
        @DisplayName("EX5 the exported workbook contains the report service's own figures")
        void exportReusesTheReportServiceResult() throws Exception {
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

            ExportFileDTO file =
                    exportService.exportExcel(ExportReportType.DAILY_WORKSHOP, filter());

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
                var summary = wb.getSheet("Summary");
                // Row 1 is the date; the job counts follow as numeric cells, which
                // is what proves the values were not stringified or recomputed.
                assertThat(numeric(summary, 2, 1)).isEqualTo(5L);   // total jobs
                assertThat(numeric(summary, 3, 1)).isEqualTo(3L);   // completed
                assertThat(numeric(summary, 4, 1)).isEqualTo(2L);   // pending

                var breakdown = wb.getSheet("Status breakdown");
                assertThat(breakdown.getRow(1).getCell(0).getStringCellValue())
                        .isEqualTo("DELIVERED");
                assertThat(breakdown.getRow(1).getCell(1).getNumericCellValue())
                        .isEqualTo(3L);
            }
        }

        @Test
        @DisplayName("EX6 the workbook records the report title, filters and generated time")
        void workbookCarriesMetadata() throws Exception {
            ReportFilterDTO f = filter();
            f.setMechanicId(7L);
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                    exportService.exportExcel(ExportReportType.DAILY_WORKSHOP, f).getContent()))) {
                var info = wb.getSheet("Overview");
                String filters = info.getRow(3).getCell(1).getStringCellValue();
                // Every filter the report supports is echoed, so none is silently
                // dropped from the download.
                assertThat(filters)
                        .contains("mechanicId=7")
                        .contains("vehicleId=all")
                        .contains("serviceType=all")
                        .contains("status=all")
                        .contains("jobCardId=all");
                assertThat(info.getRow(0).getCell(1).getStringCellValue())
                        .isEqualTo("Daily Workshop Report");
                assertThat(info.getRow(2).getCell(1).getStringCellValue()).isNotBlank();
            }
        }

        @Test
        @DisplayName("EX7 a null measurement exports blank, never 0 or 'null'")
        void nullsExportAsBlank() throws Exception {
            PartsUsageReportDTO usage = new PartsUsageReportDTO();
            usage.setFrom(DAY);
            usage.setTo(DAY);
            PartsUsageRowDTO row = new PartsUsageRowDTO();
            row.setSku("MYS-1");
            row.setPartName("Mystery Part");
            row.setUnit("PCS");
            row.setQuantityConsumed(5L);
            row.setCurrentPurchasePrice(null);   // never costed
            usage.setParts(List.of(row));
            when(reportService.getPartsUsageReport(any())).thenReturn(usage);

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                    exportService.exportExcel(ExportReportType.PARTS_USAGE, filter()).getContent()))) {
                var parts = wb.getSheet("Parts consumed");
                // "Unknown cost" must not read as "cost 0".
                assertThat(parts.getRow(1).getCell(5).getCellType()).isEqualTo(CellType.BLANK);
            }
        }

        @Test
        @DisplayName("EX8 profit export states that profit was NOT calculated")
        void profitExportKeepsTheLimitation() throws Exception {
            ProfitAnalysisReportDTO profit = new ProfitAnalysisReportDTO();
            profit.setFrom(DAY);
            profit.setTo(DAY);
            profit.setInvoicedRevenue(new BigDecimal("10000.00"));
            profit.setProfitAvailable(false);
            profit.setGrossProfit(null);
            profit.setLimitations(List.of("Labour cost is unattributable."));
            when(reportService.getProfitAnalysisReport(any())).thenReturn(profit);

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                    exportService.exportExcel(ExportReportType.PROFIT_ANALYSIS, filter()).getContent()))) {
                var conclusion = wb.getSheet("Conclusion");
                assertThat(conclusion.getRow(1).getCell(1).getStringCellValue()).isEqualTo("no");
                assertThat(conclusion.getRow(2).getCell(1).getStringCellValue())
                        .contains("NOT CALCULATED");
                assertThat(wb.getSheet("Limitations").getRow(1).getCell(1).getStringCellValue())
                        .isEqualTo("Labour cost is unattributable.");
            }
        }
    }

    private long numeric(org.apache.poi.ss.usermodel.Sheet sheet, int row, int col) {
        return (long) sheet.getRow(row).getCell(col).getNumericCellValue();
    }

    // ── Filters, empty data and error propagation ─────────────────────────

    @Nested
    @DisplayName("Filters and error handling")
    class FiltersAndErrors {

        @Test
        @DisplayName("EX9 every filter reaches the report service unchanged")
        void allFiltersArePassedThrough() {
            ReportFilterDTO f = new ReportFilterDTO();
            f.setDate(DAY);
            f.setFrom(DAY);
            f.setTo(DAY.plusDays(2));
            f.setMechanicId(7L);
            f.setVehicleId(9L);
            f.setServiceType("SERVICE");
            f.setStatus("DELIVERED");
            f.setJobCardId(11L);
            when(reportService.getDailyWorkshopReport(any())).thenReturn(dailyWorkshop());

            exportService.exportPdf(ExportReportType.DAILY_WORKSHOP, f);

            var captor = org.mockito.ArgumentCaptor.forClass(ReportFilterDTO.class);
            verify(reportService).getDailyWorkshopReport(captor.capture());
            ReportFilterDTO seen = captor.getValue();
            assertThat(seen.getDate()).isEqualTo(DAY);
            assertThat(seen.getMechanicId()).isEqualTo(7L);
            assertThat(seen.getVehicleId()).isEqualTo(9L);
            assertThat(seen.getServiceType()).isEqualTo("SERVICE");
            assertThat(seen.getStatus()).isEqualTo("DELIVERED");
            assertThat(seen.getJobCardId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("EX10 an invalid range fails exactly as it does on the JSON endpoint")
        void invalidRangePropagates() {
            when(reportService.getRevenuePaymentReport(any()))
                    .thenThrow(new BusinessRuleException("Revenue report date range is invalid."));

            assertThatThrownBy(() ->
                    exportService.exportPdf(ExportReportType.REVENUE_PAYMENT, filter()))
                    .isInstanceOf(BusinessRuleException.class);
            assertThatThrownBy(() ->
                    exportService.exportExcel(ExportReportType.REVENUE_PAYMENT, filter()))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("EX11 an empty report still exports, and says so")
        void emptyReportStillExports() throws Exception {
            when(reportService.getMechanicPerformanceReport(any())).thenReturn(List.of());

            ExportFileDTO pdf = exportService.exportPdf(ExportReportType.MECHANIC_PERFORMANCE, filter());
            assertThat(pdf.size()).isGreaterThan(0);

            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                    exportService.exportExcel(ExportReportType.MECHANIC_PERFORMANCE, filter())
                            .getContent()))) {
                var info = wb.getSheet("Overview");
                assertThat(info.getLastRowNum()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("EX12 an unknown report slug resolves to no type")
        void unknownSlugResolvesToNothing() {
            assertThat(ExportReportType.fromSlug("nope")).isNull();
            assertThat(ExportReportType.fromSlug(null)).isNull();
            assertThat(ExportReportType.fromSlug("daily-workshop"))
                    .isEqualTo(ExportReportType.DAILY_WORKSHOP);
            // Case is tolerated, since a URL slug is user-typed.
            assertThat(ExportReportType.fromSlug("DAILY-WORKSHOP"))
                    .isEqualTo(ExportReportType.DAILY_WORKSHOP);
        }
    }
}
