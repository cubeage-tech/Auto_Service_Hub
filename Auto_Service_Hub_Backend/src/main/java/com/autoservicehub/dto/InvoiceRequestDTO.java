package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

    private String status;
    private LocalDate invoiceDate;

    /** Billed lines. At least one is required — an empty invoice has no value. */
    @NotEmpty(message = "at least one line item is required")
    @Valid
    private List<InvoiceItemRequestDTO> items;
}
