package com.autoservicehub.service.impl;

import com.autoservicehub.dto.*;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.projection.DailyRevenueProjection;
import com.autoservicehub.projection.DateCountProjection;
import com.autoservicehub.projection.InvoiceStatusTotalProjection;
import com.autoservicehub.projection.MechanicJobCountProjection;
import com.autoservicehub.projection.MechanicTurnaroundProjection;
import com.autoservicehub.projection.PartUsageProjection;
import com.autoservicehub.projection.PaymentModeTotalProjection;
import com.autoservicehub.projection.StatusCountProjection;
import com.autoservicehub.entity.AiInsight;
import com.autoservicehub.repository.AiInsightRepository;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FeedbackRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.repository.StockMovementRepository;
import com.autoservicehub.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard and reports (SRS 4.11, 17; FR-REP-1..8).
 *
 * <p>Every figure is derived from stored rows via the report repository queries.
 * Nothing is estimated, and where the schema cannot support a metric the report
 * carries a null or an explicit limitation rather than a plausible substitute —
 * most importantly {@link #getProfitAnalysisReport}, which cannot produce true
 * profit because labour cost is not attributable to any job card.
 *
 * <p>Windows are half-open at the repository boundary: {@code from} is the start
 * of the first day, and the exclusive end is the start of the day after
 * {@code to}, so the whole final day is included without sub-second arithmetic.
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /** Terminal job-card state: finished work. */
    public static final String STATUS_DELIVERED = "DELIVERED";

    /** The only payment status that counts as money in. */
    public static final String PAYMENT_SUCCESS = "SUCCESS";

    /** The only stock movement meaning parts left the shelf for a customer. */
    public static final String MOVEMENT_OUT = "OUT";

    /** Report name used in AI Insights validation messages. */
    private static final String REPORT_AI_INSIGHTS = "AI Insights report";

    private final JobCardRepository       jobCardRepository;
    private final InvoiceRepository       invoiceRepository;
    private final PaymentRepository       paymentRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PartRepository          partRepository;
    private final AppointmentRepository   appointmentRepository;
    private final CustomerRepository      customerRepository;
    private final MechanicRepository      mechanicRepository;
    private final FeedbackRepository      feedbackRepository;
    private final AiInsightRepository     aiInsightRepository;

    @Override
    public DashboardSummaryDTO getDashboardSummary() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        long todaysJobs    = jobCardRepository.countByAssignedDateBetween(startOfDay, endOfDay);
        long completedJobs = jobCardRepository.countByStatus(STATUS_DELIVERED);
        long pendingJobs   = jobCardRepository.countByStatus("RECEIVED")
                           + jobCardRepository.countByStatus("INSPECTION")
                           + jobCardRepository.countByStatus("IN_REPAIR")
                           + jobCardRepository.countByStatus("QUALITY_CHECK");
        // Each part is compared against its OWN min_stock, not a hard-coded zero,
        // so the dashboard reflects the configured reorder levels.
        long lowStockParts = partRepository.countLowStock();
        long upcomingAppts = appointmentRepository.countByAppointmentAtBetween(
                LocalDateTime.now(), LocalDateTime.now().plusDays(7));

        return new DashboardSummaryDTO(
                todaysJobs, completedJobs, pendingJobs,
                nz(invoiceRepository.sumTodayRevenue()),
                lowStockParts, upcomingAppts);
    }

    // ── FR-REP-1: daily workshop ───────────────────────────────────────────

    /**
     * Jobs assigned on a single day, split into finished and still-open, with the
     * full status breakdown.
     *
     * <p>Derived entirely from the status breakdown query, so {@code totalJobs} is
     * the sum of the breakdown rather than a separate count — the two can never
     * disagree, and a client can check one against the other.
     *
     * <p>{@code pendingJobs} is everything not DELIVERED. Naming it that way is
     * deliberate: it covers work received, in repair and in QC alike, and
     * inventing separate buckets for those would require statuses this schema
     * does not define.
     */
    @Override
    public DailyWorkshopReportDTO getDailyWorkshopReport(ReportFilterDTO filter) {
        ReportFilterDTO f = filter == null ? new ReportFilterDTO() : filter;
        if (f.getDate() != null && f.getFrom() != null && f.getDate().isBefore(f.getFrom())) {
            throw invalidRange("Daily workshop report");
        }
        // This report is about ONE day. `date` is the explicit choice; a caller
        // who supplied only a range gets its start day; failing both, today.
        // Silently ignoring a supplied range would be surprising, so the range
        // start is honoured rather than discarded.
        LocalDate date = f.getDate() != null ? f.getDate()
                : f.getFrom() != null ? f.getFrom()
                : LocalDate.now();
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to   = date.plusDays(1).atStartOfDay();

        List<StatusCountProjection> breakdown = jobCardRepository.countGroupedByStatusWithFilters(
                from, to, f.getMechanicId(), f.getVehicleId(), f.getServiceType(), f.getStatus());

        DailyWorkshopReportDTO dto = new DailyWorkshopReportDTO();
        dto.setDate(date);

        long total = 0;
        long completed = 0;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (StatusCountProjection row : breakdown) {
            long count = nz(row.getStatusCount());
            total += count;
            if (STATUS_DELIVERED.equalsIgnoreCase(row.getStatus())) {
                completed += count;
            }
            // A null status still counts towards the total but has no bucket to
            // sit in, so it is skipped here rather than keyed on "null".
            if (row.getStatus() != null) {
                byStatus.put(row.getStatus(), count);
            }
        }

        dto.setTotalJobs(total);
        dto.setCompletedJobs(completed);
        dto.setPendingJobs(total - completed);
        dto.setStatusBreakdown(byStatus);
        dto.setInvoicedAmount(nz(invoiceRepository.sumTotalByJobAssignedDateBetween(from, to)));
        dto.setCollectedAmount(nz(paymentRepository.sumAmountByStatusAndPaidAtBetween(
                PAYMENT_SUCCESS, from, to)));
        dto.setLimitation(
                "Revenue figures are billed amounts only. True profit needs labour and "
                + "parts cost per job card, neither of which is stored; see /profit-analysis.");
        return dto;
    }

    // ── FR-REP-4: revenue and payments ─────────────────────────────────────

    @Override
    public RevenuePaymentReportDTO getRevenuePaymentReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, "Revenue report");
        LocalDateTime from = f.fromDateTime();
        LocalDateTime to   = f.toDateTimeExclusive();

        RevenuePaymentReportDTO dto = new RevenuePaymentReportDTO();
        dto.setFrom(f.getFrom());
        dto.setTo(f.getTo());
        dto.setMechanicId(f.getMechanicId());
        dto.setVehicleId(f.getVehicleId());
        dto.setServiceType(f.getServiceType());
        dto.setStatus(f.getStatus());

        // A job filter reaches an invoice only through its job card. The service
        // filter is NOT applied here — Invoice has no service column — so it is
        // echoed back as requested rather than silently ignored.
        boolean jobFiltered = f.getStatus() != null
                || f.getMechanicId() != null || f.getVehicleId() != null;
        BigDecimal invoiced = jobFiltered
                ? nz(invoiceRepository.sumTotalByDateRangeWithJobFilters(
                        f.getFrom(), f.getTo(), f.getStatus(), f.getMechanicId(), f.getVehicleId()))
                : nz(invoiceRepository.sumTotalByInvoiceDateBetween(f.getFrom(), f.getTo()));

        long invoiceCount = invoiceRepository.countByInvoiceDateBetween(f.getFrom(), f.getTo());
        BigDecimal collected = nz(paymentRepository.sumAmountByStatusAndPaidAtBetween(
                PAYMENT_SUCCESS, from, to));
        long paymentCount = paymentRepository
                .countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                        PAYMENT_SUCCESS, from, to);

        dto.setInvoicedRevenue(invoiced);
        dto.setInvoiceCount(invoiceCount);
        dto.setAverageInvoiceValue(average(invoiced, invoiceCount));
        dto.setCollectedAmount(collected);
        dto.setPaymentCount(paymentCount);
        dto.setAveragePaymentValue(average(collected, paymentCount));
        dto.setOutstandingAmount(invoiced.subtract(collected));

        List<PaymentModeSummaryDTO> modes = new ArrayList<>();
        for (PaymentModeTotalProjection row
                : paymentRepository.sumGroupedByMode(PAYMENT_SUCCESS, from, to)) {
            PaymentModeSummaryDTO m = new PaymentModeSummaryDTO();
            m.setMode(row.getMode());
            m.setPaymentCount(nz(row.getPaymentCount()));
            m.setTotal(nz(row.getTotal()));
            modes.add(m);
        }
        dto.setPaymentModes(modes);

        List<InvoiceStatusSummaryDTO> statuses = new ArrayList<>();
        for (InvoiceStatusTotalProjection row
                : invoiceRepository.countAndTotalGroupedByStatus(f.getFrom(), f.getTo())) {
            InvoiceStatusSummaryDTO s = new InvoiceStatusSummaryDTO();
            s.setStatus(row.getStatus());
            s.setInvoiceCount(nz(row.getInvoiceCount()));
            s.setTotal(nz(row.getTotal()));
            statuses.add(s);
        }
        dto.setInvoiceStatuses(statuses);

        List<DailyRevenueSummaryDTO> trend = new ArrayList<>();
        for (DailyRevenueProjection row
                : invoiceRepository.sumGroupedByInvoiceDate(f.getFrom(), f.getTo())) {
            DailyRevenueSummaryDTO d = new DailyRevenueSummaryDTO();
            d.setDate(row.getInvoiceDate());
            d.setInvoiceCount(nz(row.getInvoiceCount()));
            d.setTotal(nz(row.getTotal()));
            trend.add(d);
        }
        dto.setDailyTrend(trend);
        dto.setEmpty(invoiceCount == 0 && paymentCount == 0);
        return dto;
    }

    // ── FR-REP-2: mechanic performance ─────────────────────────────────────

    /**
     * One entry per mechanic over the window, ordered by job volume.
     *
     * <p>A mechanic with no jobs in the window is still returned, with zero
     * counts. Omitting them would read as "not measured" rather than "measured
     * and did nothing", which understates the problem.
     *
     * <p>Revenue per mechanic is scoped to their DELIVERED jobs, so work still in
     * progress contributes no revenue — an invoice is only raised for finished work.
     */
    @Override
    public List<MechanicPerformanceReportDTO> getMechanicPerformanceReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, "Mechanic performance report");
        LocalDateTime from = f.fromDateTime();
        LocalDateTime to   = f.toDateTimeExclusive();

        List<MechanicJobCountProjection> rows = jobCardRepository.countJobsGroupedByMechanic(
                from, to, f.getMechanicId(), STATUS_DELIVERED);

        // One query for the whole window rather than one per mechanic, so the cost
        // does not grow with the size of the workshop.
        List<MechanicTurnaroundProjection> turnaroundRows =
                jobCardRepository.findCompletedTurnaroundInPeriod(
                        from, to, f.getMechanicId(), STATUS_DELIVERED);
        Map<Long, List<Long>> turnaroundByMechanic = groupTurnaroundDays(turnaroundRows);

        List<MechanicPerformanceReportDTO> result = new ArrayList<>();
        for (MechanicJobCountProjection row : rows) {
            MechanicPerformanceReportDTO dto = new MechanicPerformanceReportDTO();
            dto.setFrom(f.getFrom());
            dto.setTo(f.getTo());
            dto.setMechanicId(row.getMechanicId());
            dto.setMechanicName(row.getMechanicName());
            dto.setEmployeeCode(row.getEmployeeCode());

            long assigned = nz(row.getAssignedJobs());
            long completed = nz(row.getCompletedJobs());
            dto.setAssignedJobs(assigned);
            dto.setCompletedJobs(completed);
            dto.setOpenJobs(Math.max(0L, assigned - completed));
            dto.setCompletionRate(rate(completed, assigned));

            BigDecimal revenue = nz(invoiceRepository.sumTotalByMechanicAndJobStatus(
                    row.getMechanicId(), STATUS_DELIVERED, from, to));
            dto.setTotalRevenue(revenue);
            dto.setAverageRevenuePerJob(average(revenue, completed));

            dto.setRatingCount(feedbackRepository.countByJobCardMechanicId(row.getMechanicId()));
            dto.setAverageCustomerRating(
                    feedbackRepository.findAverageRatingByMechanicId(row.getMechanicId()));

            // FR-MECH-5: mean elapsed days per completed job. Null, not zero, when
            // the mechanic completed nothing — "no jobs finished" is not "instant".
            dto.setAverageTurnaroundDays(
                    averageTurnaroundDays(turnaroundByMechanic.get(row.getMechanicId())));

            dto.setUnsupportedMetrics(
                    "Hours worked, shifts, capacity and utilisation are not stored "
                    + "anywhere in this schema and are not reported. "
                    + "Average turnaround is elapsed calendar time between job-card "
                    + "dates, not effort, so it needs no time-tracking table.");
            result.add(dto);
        }
        return result;
    }

    /**
     * Groups each completed job's elapsed days by mechanic (FR-MECH-5).
     *
     * <p>Whole days are used rather than a fractional value: a turnaround figure
     * is read as "how many days", and a part-day average implies precision the
     * stored timestamps do not carry. The query already excludes jobs without
     * both dates and without a mechanic.
     */
    private Map<Long, List<Long>> groupTurnaroundDays(List<MechanicTurnaroundProjection> rows) {
        Map<Long, List<Long>> byMechanic = new LinkedHashMap<>();
        for (MechanicTurnaroundProjection row : rows) {
            long days = ChronoUnit.DAYS.between(row.getAssignedDate(), row.getCompletedDate());
            // A completion recorded before assignment would be a data fault, not a
            // negative turnaround; it is dropped rather than skewing the average.
            if (days < 0) {
                continue;
            }
            byMechanic.computeIfAbsent(row.getMechanicId(), k -> new ArrayList<>()).add(days);
        }
        return byMechanic;
    }

    /** Mean turnaround in days, or null when this mechanic completed nothing. */
    private Double averageTurnaroundDays(List<Long> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        long total = 0;
        for (Long d : days) {
            total += d;
        }
        return (double) total / days.size();
    }

    // ── FR-REP-3: parts usage ──────────────────────────────────────────────

    /**
     * Parts consumed in the window, from OUT stock movements only.
     *
     * <p>IN (goods received) and ADJUSTMENT (a correction, possibly negative) are
     * not consumption, and are excluded by passing {@link #MOVEMENT_OUT} as the
     * type rather than by the query hard-coding it.
     *
     * <p>{@code estimatedCost} multiplies quantity by the part's CURRENT purchase
     * price. That is an approximation, not a historical cost: no cost as-of the
     * movement date is stored, so this is a lower bound on cost, never a margin.
     */
    @Override
    public PartsUsageReportDTO getPartsUsageReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, "Parts usage report");
        LocalDateTime from = f.fromDateTime();
        LocalDateTime to   = f.toDateTimeExclusive();

        // Only one scope may be requested; the two would otherwise contradict.
        if (f.getJobCardId() != null && f.getMechanicId() != null) {
            throw new BusinessRuleException(
                    "Parts usage accepts either jobCardId or mechanicId, not both.");
        }

        List<PartUsageProjection> rows;
        if (f.getJobCardId() != null) {
            rows = stockMovementRepository.sumUsageGroupedByPartForJobCard(
                    MOVEMENT_OUT, f.getJobCardId(), from, to);
        } else if (f.getMechanicId() != null) {
            rows = stockMovementRepository.sumUsageGroupedByPartForMechanic(
                    MOVEMENT_OUT, f.getMechanicId(), from, to);
        } else {
            rows = stockMovementRepository.sumUsageGroupedByPart(MOVEMENT_OUT, from, to);
        }

        PartsUsageReportDTO dto = new PartsUsageReportDTO();
        dto.setFrom(f.getFrom());
        dto.setTo(f.getTo());

        long totalQuantity = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        List<PartsUsageRowDTO> parts = new ArrayList<>();
        for (PartUsageProjection row : rows) {
            PartsUsageRowDTO p = new PartsUsageRowDTO();
            p.setPartId(row.getPartId());
            p.setSku(row.getSku());
            p.setPartName(row.getName());
            p.setUnit(row.getUnit());
            long quantity = nz(row.getQuantity());
            p.setQuantityConsumed(quantity);
            p.setMovementCount(nz(row.getMovementCount()));
            p.setCurrentPurchasePrice(row.getPurchasePrice());
            // A part with no recorded price still counts as consumed, but
            // contributes no cost rather than a fabricated one.
            BigDecimal cost = row.getPurchasePrice() == null
                    ? BigDecimal.ZERO
                    : row.getPurchasePrice().multiply(BigDecimal.valueOf(quantity));
            p.setEstimatedCost(cost);
            parts.add(p);
            totalQuantity += quantity;
            totalCost = totalCost.add(cost);
        }
        dto.setParts(parts);
        dto.setDistinctPartCount(parts.size());
        dto.setTotalQuantityConsumed(totalQuantity);
        dto.setTotalEstimatedCost(totalCost);
        dto.setMethodology(
                "Counted from StockMovement rows of type OUT only; IN and ADJUSTMENT are "
                + "excluded. Estimated cost uses each part's CURRENT purchase price, as no "
                + "cost as-of the movement date is stored. Job card and mechanic are left "
                + "empty per row because the grouped query does not carry them.");
        return dto;
    }

    // ── FR-REP-5: customer growth ───────────────────────────────────────────

    /**
     * New and returning customers over the window.
     *
     * <p>{@code repeatCustomerCount} is customers whose own {@code createdAt}
     * precedes the window but who have a job card inside it. That distinction is
     * the point of the metric: counting a first-time customer as repeat business
     * would inflate the figure.
     *
     * <p>{@code repeatRate} is null when nobody was served, rather than 0% — a
     * zero rate over no activity would read as total churn, which is not what
     * happened.
     */
    @Override
    public CustomerGrowthReportDTO getCustomerGrowthReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, "Customer growth report");
        LocalDateTime from = f.fromDateTime();
        LocalDateTime to   = f.toDateTimeExclusive();

        long newCustomers = customerRepository
                .countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
        long served = jobCardRepository.countDistinctCustomersWithJobs(from, to);
        long repeat = jobCardRepository.countRepeatCustomersInPeriod(from, to);
        long jobs   = jobCardRepository.countByAssignedDateRange(from, to);
        long totalCustomers = customerRepository.countByCreatedAtLessThan(to);

        // The repository groups by raw timestamp because JPQL cannot truncate a
        // date portably, so fold those buckets into calendar days here.
        Map<LocalDate, Long> newByDay = new java.util.TreeMap<>();
        for (DateCountProjection row
                : customerRepository.countNewCustomersGroupedByCreatedAt(from, to)) {
            LocalDate day = row.getCreatedAt() == null ? null : row.getCreatedAt().toLocalDate();
            if (day != null) {
                newByDay.merge(day, nz(row.getRowCount()), Long::sum);
            }
        }

        CustomerGrowthReportDTO dto = new CustomerGrowthReportDTO(
                f.getFrom(), f.getTo(),
                newCustomers, totalCustomers, repeat, served, jobs,
                served == 0 ? null
                        : BigDecimal.valueOf(repeat)
                                .divide(BigDecimal.valueOf(served), 4, RoundingMode.HALF_UP),
                "New customers were created inside the window; repeat customers existed "
                + "before it and returned during it. Growth by registration day: " + newByDay + ".");
        return dto;
    }

    // ── FR-REP-4: profit analysis ──────────────────────────────────────────

    /**
     * Revenue, the cost that genuinely exists, and an explicit statement of the rest.
     *
     * <p><strong>True profit cannot be calculated from this schema.</strong> The
     * revenue side is exact. The only cost available is parts, valued at each part's
     * current purchase price — and even that is an approximation, since no cost
     * as-of the movement date is stored. Labour cost is not merely missing a date:
     * {@code JobTask} holds a {@code labour_cost} column but has <em>no relationship
     * to {@code JobCard}</em>, so no labour figure can be attributed to any job,
     * mechanic or period at all.
     *
     * <p>Rather than report revenue minus parts cost as "profit" — which would
     * silently overstate the result — {@code grossProfit} stays null,
     * {@code profitAvailable} is false, and the reasons are enumerated. A client
     * needing margin should treat {@code partsCostEstimate} as a floor on cost,
     * not a complete one.
     */
    @Override
    public ProfitAnalysisReportDTO getProfitAnalysisReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, "Profit analysis report");
        LocalDateTime from = f.fromDateTime();
        LocalDateTime to   = f.toDateTimeExclusive();

        BigDecimal invoiced = nz(invoiceRepository.sumTotalByInvoiceDateBetween(
                f.getFrom(), f.getTo()));
        long invoiceCount = invoiceRepository.countByInvoiceDateBetween(f.getFrom(), f.getTo());
        BigDecimal collected = nz(paymentRepository.sumAmountByStatusAndPaidAtBetween(
                PAYMENT_SUCCESS, from, to));
        long paymentCount = paymentRepository
                .countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                        PAYMENT_SUCCESS, from, to);

        ProfitAnalysisReportDTO dto = new ProfitAnalysisReportDTO();
        dto.setFrom(f.getFrom());
        dto.setTo(f.getTo());
        dto.setInvoicedRevenue(invoiced);
        dto.setInvoiceCount(invoiceCount);
        dto.setCollectedAmount(collected);
        dto.setPaymentCount(paymentCount);
        dto.setOutstandingAmount(invoiced.subtract(collected));
        dto.setPartsCostEstimate(nz(stockMovementRepository
                .sumEstimatedCostByTypeAndCreatedAtBetween(MOVEMENT_OUT, from, to)));
        dto.setPartsConsumedQuantity(stockMovementRepository
                .sumQuantityByTypeAndCreatedAtBetween(MOVEMENT_OUT, from, to));
        dto.setProfitAvailable(false);
        dto.setGrossProfit(null);
        dto.setLimitations(List.of(
                "Gross profit is NOT reported. Computing it requires total cost per job, "
                + "which this schema cannot supply.",
                "Labour cost is unattributable: JobTask.labour_cost exists, but JobTask has "
                + "no relationship to JobCard, so no labour amount can be assigned to any "
                + "job, mechanic or period.",
                "Parts cost is an approximation: OUT movements are valued at each part's "
                + "CURRENT purchase_price, as no cost as-of the movement date is stored.",
                "Revenue and parts cost describe different populations — invoices raised in "
                + "the period versus parts consumed in it — so their difference is not a margin.",
                "InvoiceItem has no link to Part (only free-text description), so revenue "
                + "cannot be attributed to the specific parts a customer was charged for."));
        return dto;
    }

    // ── FR-REP-9: AI Insights report ───────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AiInsightsReportDTO getAiInsightsReport(ReportFilterDTO filter) {
        ReportFilterDTO f = requireRange(filter, REPORT_AI_INSIGHTS);

        AiInsightsReportDTO dto = new AiInsightsReportDTO();
        // The DTO reports the window as timestamps while the filter carries plain
        // dates, so the boundary is converted with the same day-start rule the
        // query uses: the inclusive start of `from`, and the start of `to` itself
        // (not the exclusive end), so the echoed range is the dates the caller
        // asked for rather than the internal query bound.
        dto.setFrom(f.getFrom().atStartOfDay());
        dto.setTo(f.getTo().atStartOfDay());

        List<AiInsight> rows = aiInsightRepository.findCreatedInPeriod(
                f.fromDateTime(), f.toDateTimeExclusive());

        List<AiInsightDTO> insights = new ArrayList<>();
        for (AiInsight row : rows) {
            insights.add(toInsightDto(row));
        }
        dto.setInsights(insights);
        // Empty is a legitimate answer — no AI feature ran in the window — so it
        // is reported as such rather than as an error or an empty envelope that a
        // client has to interpret.
        dto.setEmpty(insights.isEmpty());

        return dto;
    }

    /**
     * Maps a stored insight to its DTO.
     *
     * <p>{@code resultJson} is passed through exactly as stored. It is the
     * provider's own output, and re-parsing or reshaping it here would risk
     * misrepresenting what the provider actually said — which for an advisory
     * feature is the one thing that must not happen.
     */
    private AiInsightDTO toInsightDto(AiInsight row) {
        AiInsightDTO dto = new AiInsightDTO();
        dto.setId(row.getId());
        dto.setFeatureType(row.getFeatureType());
        dto.setInputRef(row.getInputRef());
        dto.setResultJson(row.getResultJson());
        dto.setConfidence(row.getConfidence());
        dto.setCreatedAt(row.getCreatedAt());
        return dto;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    /** Rejects a missing filter or a reversed range with the project's 409 convention. */
    private ReportFilterDTO requireRange(ReportFilterDTO filter, String reportName) {
        if (filter == null || filter.getFrom() == null || filter.getTo() == null) {
            throw new BusinessRuleException(reportName + " requires both from and to dates.");
        }
        if (filter.getFrom().isAfter(filter.getTo())) {
            throw invalidRange(reportName);
        }
        return filter;
    }

    private BusinessRuleException invalidRange(String reportName) {
        return new BusinessRuleException(
                reportName + " date range is invalid: from date cannot be after to date.");
    }

    /** Null-safe Long unboxing for projection counts, which may be null in aggregate rows. */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** Null-safe BigDecimal: an empty aggregate must read as zero, never null. */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Guards against divide-by-zero, which would otherwise surface as an ArithmeticException. */
    private static BigDecimal average(BigDecimal total, long count) {
        return count == 0 ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** Completion rate as a fraction, zero when nothing was assigned. */
    private static BigDecimal rate(long completed, long assigned) {
        return assigned == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(assigned), 4,
                        RoundingMode.HALF_UP);
    }
}