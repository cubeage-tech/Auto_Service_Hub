package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound payload for Invoice endpoints. Never expose the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class InvoiceResponseDTO {
    private Long id;
    private Long jobCardId;

    /**
     * The estimate this invoice was converted from, or null for one raised
     * directly. Lets a caller trace an invoice back to the quote it came from,
     * and shows at a glance that an estimate was already consumed.
     */
    private Long estimateId;

    private String customerName;
    private String vehicleInfo;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal gst;
    private BigDecimal total;
    private String status;
    private LocalDate invoiceDate;
    private List<InvoiceItemResponseDTO> items;

    /** Sum of this invoice's successful payments. */
    private BigDecimal amountPaid;

    /** Total minus amountPaid; never negative. */
    private BigDecimal outstandingAmount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
