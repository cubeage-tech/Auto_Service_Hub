package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-REP-4: revenue and collections over a period.
 *
 * <p>Two clocks, kept apart on purpose:
 * <ul>
 *   <li>{@code invoicedRevenue} and the invoice figures are dated by
 *       {@code invoice_date} — value billed in the window.</li>
 *   <li>{@code collectedAmount} and the payment figures are dated by
 *       {@code paid_at} — cash that actually arrived in the window.</li>
 * </ul>
 *
 * <p>They will therefore disagree whenever a payment crosses a period boundary.
 * That is a true fact about the data, not an inconsistency to be smoothed over,
 * and {@link #outstandingAmount} is derived from them deliberately rather than
 * being presented as a per-period figure.
 */
@Getter
@Setter
public class RevenuePaymentReportDTO {

    private LocalDate from;
    private LocalDate to;

    /** Filters actually applied, so the figures can be interpreted. */
    private Long   mechanicId;
    private Long   vehicleId;
    private String serviceType;
    private String status;

    // ── Revenue, by invoice date ───────────────────────────────────────────

    /** Sum of invoice totals raised in the window. */
    private BigDecimal invoicedRevenue = BigDecimal.ZERO;

    private long invoiceCount;

    /** invoicedRevenue / invoiceCount, or zero when nothing was invoiced. */
    private BigDecimal averageInvoiceValue = BigDecimal.ZERO;

    private BigDecimal totalDiscount = BigDecimal.ZERO;
    private BigDecimal totalGst      = BigDecimal.ZERO;

    /** Invoiced less collected. Can be negative if payments for earlier invoices land now. */
    private BigDecimal outstandingAmount = BigDecimal.ZERO;

    // ── Collections, by payment timestamp ───────────────────────────────────

    /** Sum of SUCCESS payments received in the window. */
    private BigDecimal collectedAmount = BigDecimal.ZERO;

    private long paymentCount;

    /** collectedAmount / paymentCount, or zero when nothing was collected. */
    private BigDecimal averagePaymentValue = BigDecimal.ZERO;

    // ── Breakdowns ─────────────────────────────────────────────────────────

    /** Successful payments grouped by mode (CASH / CARD / UPI …). */
    private List<PaymentModeSummaryDTO> paymentModes = new ArrayList<>();

    /** Invoice count and value grouped by invoice status. */
    private List<InvoiceStatusSummaryDTO> invoiceStatuses = new ArrayList<>();

    /** Invoiced value per day, for the trend chart. */
    private List<DailyRevenueSummaryDTO> dailyTrend = new ArrayList<>();

    /** True when no rows matched the filters — a valid empty report, not an error. */
    private boolean empty;
}
