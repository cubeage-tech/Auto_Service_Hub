package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-REP-4: Profit analysis for a period.
 *
 * <p>This report deliberately separates two things that are often conflated:
 *
 * <ul>
 *   <li><strong>Revenue figures</strong> — invoiced value and cash actually
 *       collected. Both come from real rows and are exact.</li>
 *   <li><strong>Cost figures</strong> — only the parts cost implied by OUT stock
 *       movements is available, and even that is an approximation (see
 *       {@link #limitations}).</li>
 * </ul>
 *
 * <p>Because labour cost is not stored anywhere in the schema, a true profit
 * figure cannot be produced. Rather than report revenue minus parts cost as
 * "profit" — which would overstate the result — {@link #grossProfit} is null,
 * {@link #profitAvailable} is false, and {@link #limitations} explains why.
 * A client that needs margin should treat {@link #partsCostEstimate} as a floor
 * on cost, not a complete one.
 */
@Getter
@Setter
public class ProfitAnalysisReportDTO {

    private LocalDate from;
    private LocalDate to;

    // ── Revenue (exact) ───────────────────────────────────────────────────

    /** Sum of invoice totals raised in the period. */
    private BigDecimal invoicedRevenue = BigDecimal.ZERO;

    private long invoiceCount;

    /** Sum of successful payments collected in the period. */
    private BigDecimal collectedAmount = BigDecimal.ZERO;

    private long paymentCount;

    /** Invoiced but still outstanding at the end of the period. */
    private BigDecimal outstandingAmount = BigDecimal.ZERO;

    // ── Cost (partial) ────────────────────────────────────────────────────

    /**
     * Parts cost implied by OUT movements in the period, valued at each part's
     * current purchase price. This is a lower bound on total cost, because
     * labour is not represented in the data at all.
     */
    private BigDecimal partsCostEstimate = BigDecimal.ZERO;

    private long partsConsumedQuantity;

    // ── Conclusion ────────────────────────────────────────────────────────

    /** Always false for this schema; see {@link #limitations}. */
    private boolean profitAvailable;

    /**
     * True profit, or null when it cannot be calculated. Null here means
     * "not computable from the stored data", not zero.
     */
    private BigDecimal grossProfit;

    /** Plain-English account of what is measured and what is missing. */
    private List<String> limitations = new ArrayList<>();
}
