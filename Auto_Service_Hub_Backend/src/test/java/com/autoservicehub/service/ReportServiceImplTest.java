package com.autoservicehub.service;

import com.autoservicehub.dto.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.projection.*;
import com.autoservicehub.repository.*;
import com.autoservicehub.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportServiceImpl} (FR-REP-1..8).
 *
 * <p>Pure Mockito against the report repositories, so these do NOT depend on the
 * H2 schema that {@code ReportRepositoryQueriesTest} is blocked by. The
 * repositories are mocked here; the JPQL itself is covered by that other class
 * once the schema issue is resolved. What these tests pin is the SERVICE logic:
 * date-window handling, filter plumbing, empty-result handling, and — most
 * importantly — that no metric is invented when the data cannot support it.
 *
 * Test groups
 * -----------
 * RS1–RS7  daily workshop        RS8–RS14 revenue and payments
 * RS15–RS21 mechanic performance RS22–RS27 parts usage
 * RS28–RS32 customer growth     RS33–RS39 profit analysis and validation
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceImplTest {

    @Mock JobCardRepository       jobCardRepository;
    @Mock InvoiceRepository       invoiceRepository;
    @Mock PaymentRepository       paymentRepository;
    @Mock StockMovementRepository stockMovementRepository;
    @Mock PartRepository          partRepository;
    @Mock AppointmentRepository   appointmentRepository;
    @Mock CustomerRepository      customerRepository;
    @Mock MechanicRepository      mechanicRepository;
    @Mock FeedbackRepository      feedbackRepository;

    @InjectMocks ReportServiceImpl service;

    private static final LocalDate      DAY   = LocalDate.of(2026, 3, 10);
    private static final LocalDateTime  FROM  = DAY.atStartOfDay();
    private static final LocalDateTime  TO    = DAY.plusDays(1).atStartOfDay();
    private static final String         OUT   = "OUT";
    private static final String         SUCCESS = "SUCCESS";
    private static final String         DELIVERED = "DELIVERED";

    private ReportFilterDTO filter() {
        ReportFilterDTO f = new ReportFilterDTO();
        f.setFrom(DAY);
        f.setTo(DAY);
        return f;
    }

    private StatusCountProjection statusRow(String status, long count) {
        StatusCountProjection p = new StatusCountProjection() {
            @Override public String getStatus()      { return status; }
            @Override public Long   getStatusCount() { return count; }
        };
        return p;
    }

    private PartUsageProjection partRow(Long id, String sku, String name, String unit,
                                        long qty, long movements, String purchasePrice) {
        return new PartUsageProjection() {
            @Override public Long getPartId()        { return id; }
            @Override public String getSku()          { return sku; }
            @Override public String getName()         { return name; }
            @Override public String getUnit()         { return unit; }
            @Override public Long getQuantity()       { return qty; }
            @Override public Long getMovementCount()  { return movements; }
            @Override public BigDecimal getPurchasePrice() {
                return purchasePrice == null ? null : new BigDecimal(purchasePrice);
            }
        };
    }

    private MechanicJobCountProjection mechanicRow(Long id, String name, String code,
                                                   long assigned, long completed) {
        return new MechanicJobCountProjection() {
            @Override public Long getMechanicId()      { return id; }
            @Override public String getMechanicName()   { return name; }
            @Override public String getEmployeeCode()   { return code; }
            @Override public Long getAssignedJobs()     { return assigned; }
            @Override public Long getCompletedJobs()    { return completed; }
        };
    }

    private DateCountProjection dateCount(LocalDateTime at, long count) {
        return new DateCountProjection() {
            @Override public LocalDateTime getCreatedAt() { return at; }
            @Override public Long getRowCount()       { return count; }
        };
    }

    // ── FR-REP-1: daily workshop ───────────────────────────────────────────

    @Nested
    @DisplayName("Daily workshop report")
    class DailyWorkshop {

        @Test
        @DisplayName("RS1 counts total, completed and pending from the status breakdown")
        void splitsCompletedAndPending() {
            when(jobCardRepository.countGroupedByStatusWithFilters(
                    any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(statusRow("DELIVERED", 3L), statusRow("IN_REPAIR", 2L)));

            DailyWorkshopReportDTO dto = service.getDailyWorkshopReport(filter());

            assertThat(dto.getDate()).isEqualTo(DAY);
            assertThat(dto.getTotalJobs()).isEqualTo(5L);
            assertThat(dto.getCompletedJobs()).isEqualTo(3L);
            assertThat(dto.getPendingJobs()).isEqualTo(2L);
            assertThat(dto.getStatusBreakdown()).containsEntry("DELIVERED", 3L)
                                                  .containsEntry("IN_REPAIR", 2L);
        }

        @Test
        @DisplayName("RS2 the total always equals the sum of the breakdown")
        void totalEqualsBreakdownSum() {
            when(jobCardRepository.countGroupedByStatusWithFilters(
                    any(), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(statusRow("RECEIVED", 1L),
                                         statusRow("QUALITY_CHECK", 4L),
                                         statusRow("DELIVERED", 2L)));

            DailyWorkshopReportDTO dto = service.getDailyWorkshopReport(filter());

            long summed = dto.getStatusBreakdown().values().stream().mapToLong(Long::longValue).sum();
            assertThat(summed).isEqualTo(dto.getTotalJobs());
            assertThat(dto.getCompletedJobs() + dto.getPendingJobs()).isEqualTo(dto.getTotalJobs());
        }

        @Test
        @DisplayName("RS3 an empty day returns zeroes rather than nulls")
        void emptyDayReturnsZeroes() {
            when(jobCardRepository.countGroupedByStatusWithFilters(
                    any(), any(), any(), any(), any(), any())).thenReturn(List.of());
            when(invoiceRepository.sumTotalByJobAssignedDateBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            DailyWorkshopReportDTO dto = service.getDailyWorkshopReport(filter());

            assertThat(dto.getTotalJobs()).isZero();
            assertThat(dto.getCompletedJobs()).isZero();
            assertThat(dto.getPendingJobs()).isZero();
            assertThat(dto.getStatusBreakdown()).isEmpty();
            assertThat(dto.getInvoicedAmount()).isNotNull();
            assertThat(dto.getCollectedAmount()).isNotNull();
        }

        @Test
        @DisplayName("RS4 null aggregates from the database read as zero")
        void nullAggregatesBecomeZero() {
            when(jobCardRepository.countGroupedByStatusWithFilters(
                    any(), any(), any(), any(), any(), any())).thenReturn(List.of());
            when(invoiceRepository.sumTotalByJobAssignedDateBetween(any(), any())).thenReturn(null);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(null);

            DailyWorkshopReportDTO dto = service.getDailyWorkshopReport(filter());

            assertThat(dto.getInvoicedAmount()).isEqualByComparingTo("0");
            assertThat(dto.getCollectedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("RS5 the date defaults to today when not supplied")
        void defaultsToToday() {
            when(jobCardRepository.countGroupedByStatusWithFilters(
                    any(), any(), any(), any(), any(), any())).thenReturn(List.of());

            DailyWorkshopReportDTO dto = service.getDailyWorkshopReport(null);

            assertThat(dto.getDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("RS6 mechanic, vehicle, service and status filters reach the query")
        void filtersArePassedThrough() {
            ReportFilterDTO f = filter();
            f.setMechanicId(7L);
            f.setVehicleId(9L);
            f.setServiceType("SERVICE");
            f.setStatus("DELIVERED");
            when(jobCardRepository.countGroupedByStatusWithFilters(any(), any(), any(), any(),
                    any(), any())).thenReturn(List.of());

            service.getDailyWorkshopReport(f);

            verify(jobCardRepository).countGroupedByStatusWithFilters(
                    eq(FROM), eq(TO), eq(7L), eq(9L), eq("SERVICE"), eq("DELIVERED"));
        }

        @Test
        @DisplayName("RS7 the window covers the whole day, not to midnight exclusive")
        void windowCoversTheWholeDay() {
            ReportFilterDTO f = new ReportFilterDTO();
            f.setDate(DAY);
            when(jobCardRepository.countGroupedByStatusWithFilters(any(), any(), any(), any(),
                    any(), any())).thenReturn(List.of());

            service.getDailyWorkshopReport(f);

            verify(jobCardRepository).countGroupedByStatusWithFilters(
                    eq(DAY.atStartOfDay()), eq(DAY.plusDays(1).atStartOfDay()),
                    any(), any(), any(), any());
        }
    }

    // ── FR-REP-4: revenue and payments ─────────────────────────────────────

    @Nested
    @DisplayName("Revenue and payments")
    class RevenueAndPayments {

        @Test
        @DisplayName("RS8 revenue and collections are reported separately")
        void reportsRevenueAndCollections() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(DAY, DAY))
                    .thenReturn(new BigDecimal("5000.00"));
            when(invoiceRepository.countByInvoiceDateBetween(DAY, DAY)).thenReturn(4L);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(SUCCESS, FROM, TO))
                    .thenReturn(new BigDecimal("3000.00"));
            when(paymentRepository
                    .countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                            SUCCESS, FROM, TO)).thenReturn(3L);

            RevenuePaymentReportDTO dto = service.getRevenuePaymentReport(filter());

            assertThat(dto.getInvoicedRevenue()).isEqualByComparingTo("5000.00");
            assertThat(dto.getInvoiceCount()).isEqualTo(4L);
            assertThat(dto.getAverageInvoiceValue()).isEqualByComparingTo("1250.00");
            assertThat(dto.getCollectedAmount()).isEqualByComparingTo("3000.00");
            assertThat(dto.getPaymentCount()).isEqualTo(3L);
            assertThat(dto.getAveragePaymentValue()).isEqualByComparingTo("1000.00");
            assertThat(dto.getOutstandingAmount()).isEqualByComparingTo("2000.00");
            assertThat(dto.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("RS9 only SUCCESS payments are counted as collected")
        void countsOnlySuccessfulPayments() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(0L);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            service.getRevenuePaymentReport(filter());

            verify(paymentRepository).sumAmountByStatusAndPaidAtBetween(SUCCESS, FROM, TO);
        }

        @Test
        @DisplayName("RS10 an empty period reports zero averages instead of dividing by zero")
        void emptyPeriodIsSafe() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any())).thenReturn(null);
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(0L);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(null);
            when(paymentRepository
                    .countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                            anyString(), any(), any())).thenReturn(0L);

            RevenuePaymentReportDTO dto = service.getRevenuePaymentReport(filter());

            assertThat(dto.getInvoicedRevenue()).isEqualByComparingTo("0");
            assertThat(dto.getAverageInvoiceValue()).isEqualByComparingTo("0");
            assertThat(dto.getAveragePaymentValue()).isEqualByComparingTo("0");
            assertThat(dto.getPaymentModes()).isEmpty();
            assertThat(dto.getInvoiceStatuses()).isEmpty();
            assertThat(dto.getDailyTrend()).isEmpty();
            assertThat(dto.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("RS11 job filters use the job-scoped revenue query")
        void jobFiltersUseTheJobScopedQuery() {
            ReportFilterDTO f = filter();
            f.setMechanicId(7L);
            when(invoiceRepository.sumTotalByDateRangeWithJobFilters(any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("800.00"));
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(1L);

            RevenuePaymentReportDTO dto = service.getRevenuePaymentReport(f);

            assertThat(dto.getInvoicedRevenue()).isEqualByComparingTo("800.00");
            verify(invoiceRepository).sumTotalByDateRangeWithJobFilters(
                    DAY, DAY, null, 7L, null);
        }

        @Test
        @DisplayName("RS12 the requested filters are echoed back for interpretation")
        void echoesFilters() {
            ReportFilterDTO f = filter();
            f.setMechanicId(7L);
            f.setVehicleId(9L);
            f.setStatus("DELIVERED");
            when(invoiceRepository.sumTotalByDateRangeWithJobFilters(any(), any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(0L);

            RevenuePaymentReportDTO dto = service.getRevenuePaymentReport(f);

            assertThat(dto.getMechanicId()).isEqualTo(7L);
            assertThat(dto.getVehicleId()).isEqualTo(9L);
            assertThat(dto.getStatus()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("RS13 the date range end includes the whole final day")
        void rangeEndIsInclusiveOfTheFinalDay() {
            ReportFilterDTO f = filter();
            f.setTo(DAY.plusDays(4));
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(0L);

            service.getRevenuePaymentReport(f);

            // Exclusive end is the START of the day AFTER the last one asked for.
            verify(paymentRepository).sumAmountByStatusAndPaidAtBetween(
                    SUCCESS, FROM, DAY.plusDays(5).atStartOfDay());
        }
    }

    // ── FR-REP-2: mechanic performance ─────────────────────────────────────

    @Nested
    @DisplayName("Mechanic performance")
    class MechanicPerformanceReport {

        @Test
        @DisplayName("RS14 reports assigned, completed and open per mechanic")
        void reportsCountsPerMechanic() {
            when(jobCardRepository.countJobsGroupedByMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of(mechanicRow(1L, "Anil", "MECH-1", 5L, 3L)));
            when(invoiceRepository.sumTotalByMechanicAndJobStatus(any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("6000.00"));
            when(feedbackRepository.countByJobCardMechanicId(1L)).thenReturn(4L);
            when(feedbackRepository.findAverageRatingByMechanicId(1L)).thenReturn(4.5);

            List<MechanicPerformanceReportDTO> rows = service.getMechanicPerformanceReport(filter());

            assertThat(rows).hasSize(1);
            MechanicPerformanceReportDTO dto = rows.get(0);
            assertThat(dto.getMechanicName()).isEqualTo("Anil");
            assertThat(dto.getEmployeeCode()).isEqualTo("MECH-1");
            assertThat(dto.getAssignedJobs()).isEqualTo(5L);
            assertThat(dto.getCompletedJobs()).isEqualTo(3L);
            assertThat(dto.getOpenJobs()).isEqualTo(2L);
            assertThat(dto.getCompletionRate()).isEqualByComparingTo("0.6000");
            assertThat(dto.getTotalRevenue()).isEqualByComparingTo("6000.00");
            assertThat(dto.getAverageRevenuePerJob()).isEqualByComparingTo("2000.00");
            assertThat(dto.getAverageCustomerRating()).isEqualTo(4.5);
            assertThat(dto.getRatingCount()).isEqualTo(4L);
        }

        @Test
        @DisplayName("RS15 a mechanic with no jobs appears with zero counts, not omitted")
        void idleMechanicStillAppears() {
            when(jobCardRepository.countJobsGroupedByMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of(mechanicRow(2L, "Sur", "MECH-2", 0L, 0L)));
            when(invoiceRepository.sumTotalByMechanicAndJobStatus(any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(feedbackRepository.countByJobCardMechanicId(anyLong())).thenReturn(0L);
            when(feedbackRepository.findAverageRatingByMechanicId(anyLong())).thenReturn(null);

            List<MechanicPerformanceReportDTO> rows = service.getMechanicPerformanceReport(filter());

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getAssignedJobs()).isZero();
            assertThat(rows.get(0).getCompletionRate()).isEqualByComparingTo("0");
            // Unrated is null, never 0 — "no ratings" is not "rated zero".
            assertThat(rows.get(0).getAverageCustomerRating()).isNull();
        }

        @Test
        @DisplayName("RS16 the mechanic filter is passed to the aggregation")
        void mechanicFilterIsPassedThrough() {
            ReportFilterDTO f = filter();
            f.setMechanicId(3L);
            when(jobCardRepository.countJobsGroupedByMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of());

            service.getMechanicPerformanceReport(f);

            verify(jobCardRepository).countJobsGroupedByMechanic(FROM, TO, 3L, DELIVERED);
        }

        @Test
        @DisplayName("RS17 an empty window returns an empty list, not null")
        void emptyWindowReturnsEmptyList() {
            when(jobCardRepository.countJobsGroupedByMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of());

            assertThat(service.getMechanicPerformanceReport(filter())).isEmpty();
        }

        @Test
        @DisplayName("RS18 unsupported metrics are named rather than estimated")
        void namesUnsupportedMetrics() {
            when(jobCardRepository.countJobsGroupedByMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of(mechanicRow(1L, "Anil", "MECH-1", 1L, 1L)));
            when(invoiceRepository.sumTotalByMechanicAndJobStatus(any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            List<MechanicPerformanceReportDTO> rows = service.getMechanicPerformanceReport(filter());

            assertThat(rows.get(0).getUnsupportedMetrics()).contains("Hours worked");
        }
    }

    // ── FR-REP-3: parts usage ──────────────────────────────────────────────

    @Nested
    @DisplayName("Parts usage")
    class PartsUsageReport {

        @Test
        @DisplayName("RS19 only OUT movements are requested, never IN or ADJUSTMENT")
        void requestsOutMovementsOnly() {
            when(stockMovementRepository.sumUsageGroupedByPart(any(), any(), any()))
                    .thenReturn(List.of());

            service.getPartsUsageReport(filter());

            verify(stockMovementRepository).sumUsageGroupedByPart(OUT, FROM, TO);
        }

        @Test
        @DisplayName("RS20 quantity and estimated cost are rolled up per part")
        void rollsUpPerPart() {
            when(stockMovementRepository.sumUsageGroupedByPart(any(), any(), any()))
                    .thenReturn(List.of(
                            partRow(1L, "BRK-1", "Brake Pad", "PCS", 4L, 2L, "500.00"),
                            partRow(2L, "OIL-1", "Engine Oil", "LTR", 6L, 1L, "300.00")));

            PartsUsageReportDTO dto = service.getPartsUsageReport(filter());

            assertThat(dto.getDistinctPartCount()).isEqualTo(2L);
            assertThat(dto.getTotalQuantityConsumed()).isEqualTo(10L);
            // 4x500 + 6x300
            assertThat(dto.getTotalEstimatedCost()).isEqualByComparingTo("3800.00");
            assertThat(dto.getParts()).hasSize(2);
            assertThat(dto.getParts().get(0).getSku()).isEqualTo("BRK-1");
            assertThat(dto.getParts().get(0).getEstimatedCost()).isEqualByComparingTo("2000.00");
        }

        @Test
        @DisplayName("RS21 a part with no purchase price still counts, at no invented cost")
        void partWithoutPriceContributesNoCost() {
            when(stockMovementRepository.sumUsageGroupedByPart(any(), any(), any()))
                    .thenReturn(List.of(partRow(1L, "MYS-1", "Mystery", "PCS", 5L, 1L, null)));

            PartsUsageReportDTO dto = service.getPartsUsageReport(filter());

            assertThat(dto.getTotalQuantityConsumed()).isEqualTo(5L);
            assertThat(dto.getTotalEstimatedCost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("RS22 no consumption returns an empty report")
        void noConsumptionIsEmpty() {
            when(stockMovementRepository.sumUsageGroupedByPart(any(), any(), any()))
                    .thenReturn(List.of());

            PartsUsageReportDTO dto = service.getPartsUsageReport(filter());

            assertThat(dto.getParts()).isEmpty();
            assertThat(dto.getDistinctPartCount()).isZero();
            assertThat(dto.getTotalQuantityConsumed()).isZero();
            assertThat(dto.getTotalEstimatedCost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("RS23 a job-card scope uses the job-scoped query")
        void jobCardScopeUsesJobScopedQuery() {
            ReportFilterDTO f = filter();
            f.setJobCardId(11L);
            when(stockMovementRepository.sumUsageGroupedByPartForJobCard(any(), any(), any(), any()))
                    .thenReturn(List.of());

            service.getPartsUsageReport(f);

            verify(stockMovementRepository).sumUsageGroupedByPartForJobCard(OUT, 11L, FROM, TO);
        }

        @Test
        @DisplayName("RS24 a mechanic scope uses the mechanic-scoped query")
        void mechanicScopeUsesMechanicScopedQuery() {
            ReportFilterDTO f = filter();
            f.setMechanicId(5L);
            when(stockMovementRepository.sumUsageGroupedByPartForMechanic(any(), any(), any(), any()))
                    .thenReturn(List.of());

            service.getPartsUsageReport(f);

            verify(stockMovementRepository).sumUsageGroupedByPartForMechanic(OUT, 5L, FROM, TO);
        }

        @Test
        @DisplayName("RS25 asking for both a job card and a mechanic is rejected")
        void rejectsConflictingScopes() {
            ReportFilterDTO f = filter();
            f.setJobCardId(11L);
            f.setMechanicId(5L);

            assertThatThrownBy(() -> service.getPartsUsageReport(f))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("not both");
        }

        @Test
        @DisplayName("RS26 the methodology states that IN and ADJUSTMENT are excluded")
        void statesItsMethodology() {
            when(stockMovementRepository.sumUsageGroupedByPart(any(), any(), any()))
                    .thenReturn(List.of());

            PartsUsageReportDTO dto = service.getPartsUsageReport(filter());

            assertThat(dto.getMethodology()).contains("OUT").contains("CURRENT purchase price");
        }
    }

    // ── FR-REP-5: customer growth ───────────────────────────────────────────

    @Nested
    @DisplayName("Customer growth")
    class CustomerGrowthReport {

        @Test
        @DisplayName("RS27 new, served and repeat customers are reported separately")
        void reportsNewAndRepeatCustomers() {
            when(customerRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(FROM, TO))
                    .thenReturn(3L);
            when(customerRepository.countByCreatedAtLessThan(TO)).thenReturn(120L);
            when(jobCardRepository.countDistinctCustomersWithJobs(FROM, TO)).thenReturn(8L);
            when(jobCardRepository.countRepeatCustomersInPeriod(FROM, TO)).thenReturn(5L);
            when(jobCardRepository.countByAssignedDateRange(FROM, TO)).thenReturn(10L);

            CustomerGrowthReportDTO dto = service.getCustomerGrowthReport(filter());

            assertThat(dto.getNewCustomerCount()).isEqualTo(3L);
            assertThat(dto.getTotalCustomersAtEndOfPeriod()).isEqualTo(120L);
            assertThat(dto.getRepeatCustomerCount()).isEqualTo(5L);
            assertThat(dto.getCustomersServedInPeriod()).isEqualTo(8L);
            assertThat(dto.getJobsInPeriod()).isEqualTo(10L);
            assertThat(dto.getRepeatRate()).isEqualByComparingTo("0.6250");
        }

        @Test
        @DisplayName("RS28 the repeat rate is null when nobody was served, not 0%")
        void repeatRateIsNullWhenNobodyServed() {
            when(jobCardRepository.countDistinctCustomersWithJobs(any(), any())).thenReturn(0L);
            when(jobCardRepository.countRepeatCustomersInPeriod(any(), any())).thenReturn(0L);

            CustomerGrowthReportDTO dto = service.getCustomerGrowthReport(filter());

            // 0% would read as total churn, which is not what happened.
            assertThat(dto.getRepeatRate()).isNull();
        }

        @Test
        @DisplayName("RS29 time-based growth is bucketed by calendar day")
        void bucketsGrowthByDay() {
            when(customerRepository.countNewCustomersGroupedByCreatedAt(any(), any()))
                    .thenReturn(List.of(
                            dateCount(LocalDateTime.of(2026, 3, 10, 9, 0), 2L),
                            dateCount(LocalDateTime.of(2026, 3, 10, 17, 0), 3L)));
            when(jobCardRepository.countDistinctCustomersWithJobs(any(), any())).thenReturn(1L);
            when(jobCardRepository.countRepeatCustomersInPeriod(any(), any())).thenReturn(0L);

            CustomerGrowthReportDTO dto = service.getCustomerGrowthReport(filter());

            // Both buckets are on the same day, so they fold together.
            assertThat(dto.getNotes()).contains("2026-03-10=5");
        }
    }

    // ── FR-REP-4: profit analysis ──────────────────────────────────────────

    @Nested
    @DisplayName("Profit analysis")
    class ProfitAnalysisReport {

        @Test
        @DisplayName("RS30 true profit is NOT reported, because it cannot be calculated")
        void doesNotFabricateProfit() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any()))
                    .thenReturn(new BigDecimal("10000.00"));
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(5L);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(new BigDecimal("8000.00"));
            when(stockMovementRepository.sumEstimatedCostByTypeAndCreatedAtBetween(any(), any(), any()))
                    .thenReturn(new BigDecimal("2500.00"));
            when(stockMovementRepository.sumQuantityByTypeAndCreatedAtBetween(any(), any(), any()))
                    .thenReturn(10L);

            ProfitAnalysisReportDTO dto = service.getProfitAnalysisReport(filter());

            assertThat(dto.isProfitAvailable()).isFalse();
            // No fabricated margin, even though revenue minus parts cost would
            // have produced a plausible-looking 7500.
            assertThat(dto.getGrossProfit()).isNull();
            assertThat(dto.getInvoicedRevenue()).isEqualByComparingTo("10000.00");
            assertThat(dto.getCollectedAmount()).isEqualByComparingTo("8000.00");
            assertThat(dto.getPartsCostEstimate()).isEqualByComparingTo("2500.00");
            assertThat(dto.getPartsConsumedQuantity()).isEqualTo(10L);
        }

        @Test
        @DisplayName("RS31 the limitations name the missing labour cost and its cause")
        void statesWhyProfitIsUnavailable() {
            ProfitAnalysisReportDTO dto = service.getProfitAnalysisReport(filter());

            assertThat(dto.getLimitations()).isNotEmpty();
            assertThat(dto.getLimitations().toString())
                    .contains("JobTask")
                    .contains("no relationship to JobCard");
        }

        @Test
        @DisplayName("RS32 outstanding is invoiced less collected")
        void computesOutstanding() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any()))
                    .thenReturn(new BigDecimal("1000.00"));
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(new BigDecimal("250.00"));

            ProfitAnalysisReportDTO dto = service.getProfitAnalysisReport(filter());

            assertThat(dto.getOutstandingAmount()).isEqualByComparingTo("750.00");
        }

        @Test
        @DisplayName("RS33 a period with no activity is all zeroes, not nulls")
        void emptyPeriodIsAllZeroes() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any())).thenReturn(null);
            when(paymentRepository.sumAmountByStatusAndPaidAtBetween(any(), any(), any()))
                    .thenReturn(null);
            when(stockMovementRepository.sumEstimatedCostByTypeAndCreatedAtBetween(any(), any(), any()))
                    .thenReturn(null);

            ProfitAnalysisReportDTO dto = service.getProfitAnalysisReport(filter());

            assertThat(dto.getInvoicedRevenue()).isEqualByComparingTo("0");
            assertThat(dto.getCollectedAmount()).isEqualByComparingTo("0");
            assertThat(dto.getPartsCostEstimate()).isEqualByComparingTo("0");
            assertThat(dto.getInvoiceCount()).isZero();
            assertThat(dto.isProfitAvailable()).isFalse();
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("RS34 a reversed date range is rejected on every ranged report")
        void rejectsReversedRange() {
            ReportFilterDTO f = new ReportFilterDTO();
            f.setFrom(DAY);
            f.setTo(DAY.minusDays(5));

            assertThatThrownBy(() -> service.getRevenuePaymentReport(f))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.getPartsUsageReport(f))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.getCustomerGrowthReport(f))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.getProfitAnalysisReport(f))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("invalid");
            assertThatThrownBy(() -> service.getMechanicPerformanceReport(f))
                    .isInstanceOf(BusinessRuleException.class).hasMessageContaining("invalid");
        }

        @Test
        @DisplayName("RS35 a missing date range is rejected rather than defaulted")
        void rejectsMissingRange() {
            assertThatThrownBy(() -> service.getRevenuePaymentReport(new ReportFilterDTO()))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("requires both from and to");
            assertThatThrownBy(() -> service.getPartsUsageReport(null))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("RS36 a single-day range is valid, since from may equal to")
        void singleDayRangeIsValid() {
            when(invoiceRepository.sumTotalByInvoiceDateBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(invoiceRepository.countByInvoiceDateBetween(any(), any())).thenReturn(0L);

            assertThat(service.getRevenuePaymentReport(filter())).isNotNull();
        }
    }
}
