package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inbound payload for Invoice create/update endpoints. Billing - Invoices (SRS 4.9)
 *
 * <p>An invoice bills a {@link JobCard}, so {@code jobCardId} is required. As
 * with estimates, {@code subtotal}, {@code gst} and {@code total} are
 * <strong>calculated on the server</strong> from the line items and any values
 * sent for them are ignored. {@code discount} is the one client-supplied money
 * field, and it is validated against the calculated subtotal.
 */
@Getter
@Setter
public class InvoiceRequestDTO {
    @NotNull(message = "jobCardId is required")
    private Long jobCardId;

    /** Flat discount amount. Must not be negative or exceed the subtotal. */
    private BigDecimal discount;

    /** Accepted for backwards compatibility but IGNORED — the server recalculates. */
    private BigDecimal subtotal;
    private BigDecimal gst;
    private BigDecimal total;

    /**
     * GST percentage to apply to this invoice, e.g. {@code 18}, {@code 5},
     * {@code 12} or {@code 28}.
     *
     * <p>Null means "use the workshop default"
     * ({@code app.billing.tax-rate-percent}), which is what every client sent
     * before this field existed. A supplied rate is validated to 0-100 and is
     * the only rate decision a client may make — the resulting GST and total
     * are still calculated here, never taken from the request.
     */
    private BigDecimal gstRate;

    private String status;
    private LocalDate invoiceDate;

    /**
     * Optional free-text remarks shown on the invoice (FR-BILL-9).
     *
     * <p>Optional, and blank is treated as "no notes" rather than as an error —
     * an invoice with nothing to add is normal. Bound at 2000 characters, the
     * same ceiling the other free-text notes fields in this project use, so an
     * oversized note is rejected at the edge instead of being silently
     * truncated by the column.
     */
    @Size(max = 2000, message = "notes must not exceed 2000 characters")
    private String notes;

    /** Billed lines. At least one is required — an empty invoice has no value. */
    @NotEmpty(message = "at least one line item is required")
    @Valid
    private List<InvoiceItemRequestDTO> items;
}
